package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.entity.DispatchPlan;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.report.investigation.InvestigationTestSupport.*;

/** UUID隔离MySQL库验证最新初始化、幂等、认领、取消与来源快照；不调用模型或修改日常业务库。 */
@EnabledIfEnvironmentVariable(named="TRACE_IT",matches="true")
class InvestigationDatabaseTest {
    static String schema;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    InvestigationRepository repo;
    InvestigationFacts facts;
    InvestigationProperties props;
    static String env(String key,String fallback) {return System.getenv().getOrDefault(key,fallback);}
    @BeforeAll static void database() {
        schema="investigation_it_"+UUID.randomUUID().toString().replace("-","");
        var ds=new DriverManagerDataSource("jdbc:mysql://"+env("TRACE_DB_HOST","127.0.0.1")+":"+env("TRACE_DB_PORT","3306")+"/"+schema+"?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",env("TRACE_DB_USER","root"),env("TRACE_DB_PASSWORD",""));
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);Flyway.configure().dataSource(ds).load().migrate();
    }
    @AfterAll static void drop() {if(schema.matches("investigation_it_[a-f0-9]{32}") && schema.equals(jdbc.queryForObject("SELECT DATABASE()",String.class))) jdbc.execute("DROP DATABASE `"+schema+"`");}
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM agent_investigation_step");jdbc.update("DELETE FROM agent_investigation_evidence");jdbc.update("DELETE FROM agent_investigation_run");
        jdbc.update("DELETE FROM dispatch_plan_item");jdbc.update("DELETE FROM dispatch_plan");jdbc.update("DELETE FROM dispatch_preview");
        jdbc.update("INSERT INTO dispatch_preview(id,tenant_id,user_id,source,report_ids,company_codes,query_json,catalog_version,rule_version,permission_version,status,expires_at,created_at,updated_at) VALUES ('preview','T001','reader','agent','[]','[]','{}','v','v','v','ACTIVE',DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW())");
        jdbc.update("INSERT INTO dispatch_plan(id,preview_id,tenant_id,user_id,status,item_count,success_count,failed_count,idempotency_key,created_at,expires_at,updated_at,execution_version) VALUES ('plan','preview','T001','reader','REVIEW_REQUIRED',1,0,1,'plan-key',NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),1)");
        jdbc.update("INSERT INTO dispatch_plan_item(fields_json,id,plan_id,seq,report_id,report_name,catalog_version,record_id,company_code,status,external_request_id,updated_at) VALUES ('[]',9007199254740993,'plan',1,'rpt-sales-order','销售报表',1,'1','A','UNKNOWN','req',NOW())");
        props=new InvestigationProperties();repo=new InvestigationRepository(jdbc,manager,props);
        var access=mock(InvestigationAccessPolicy.class);var plan=new DispatchPlan();plan.setId("plan");plan.setUserId("reader");plan.setStatus("REVIEW_REQUIRED");when(access.require(any(),eq("plan"))).thenReturn(plan);
        facts=new InvestigationFacts(jdbc,access,props);
    }
    Map<String,Object> request() {return Map.of("planId","plan","ownerId","reader","question","分析异常","selectedItemIds",List.of("9007199254740993"));}
    Map<String,Object> create(String key) {return repo.create(ACTOR,request(),key,"hash",Map.of("model","test"),() -> 1L).run();}
    @Test void tenantSpecificClaimRecoveryAndCleanupLeaveForeignRunsUntouched() {
        String id=create("foreign").get("id").toString();
        jdbc.update("UPDATE agent_investigation_run SET tenant_id='T002' WHERE id=?",id);
        assertNull(repo.claim("T001"));assertEquals("QUEUED",repo.find(id).get("status"));
        var foreign=repo.claim("T002");assertEquals(id,foreign.get("id"));
        jdbc.update("UPDATE agent_investigation_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",id);
        repo.recover("T001");assertEquals("RUNNING",repo.find(id).get("status"));
        repo.recover("T002");assertEquals("INTERRUPTED",repo.find(id).get("status"));
        jdbc.update("UPDATE agent_investigation_run SET finished_at=TIMESTAMPADD(DAY,-30,UTC_TIMESTAMP(3)) WHERE id=?",id);
        repo.clean("T001");assertEquals(id,repo.find(id).get("id"));
        repo.clean("T002");assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_investigation_run WHERE id=?",Integer.class,id));
    }
    @Test void concurrentSameKeyReturnsOneRunAndChangedPayloadFails() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(() -> create("same-key"));var b=pool.submit(() -> create("same-key"));assertEquals(a.get().get("id"),b.get().get("id"));}
        finally {pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_investigation_run",Integer.class));
        assertThrows(ApiException.class,() -> repo.create(ACTOR,request(),"same-key","changed",Map.of("model","test"),() -> 1L));
        assertThrows(ApiException.class,() -> create("different-key"));
    }
    @Test void twoInstancesClaimOnceAndCancelBlocksLateEvidenceAndFinalReport() throws Exception {
        create("key");var second=new InvestigationRepository(jdbc,manager,props);var pool=Executors.newFixedThreadPool(2);Map<String,Object> run;
        try {var a=pool.submit(() -> repo.claim("T001"));var b=pool.submit(() -> second.claim("T001"));var x=a.get();var y=b.get();assertTrue((x==null)!=(y==null));run=x==null?y:x;}
        finally {pool.shutdownNow();}
        String id=run.get("id").toString(),token=run.get("claim_token").toString();repo.startStep(id,token,"MODEL",null,null,Map.of());repo.cancel(id);
        assertThrows(InvestigationFailure.class,() -> repo.evidence(id,token,"MCP_LOOKUP",List.of("I1"),Map.of(),Map.of(),false));
        assertThrows(InvestigationFailure.class,() -> repo.finish(id,token,"COMPLETED","NORMAL","done",Map.of(),Map.of(),() -> {}));
        assertEquals("CANCELLED",repo.find(id).get("status"));assertEquals("FAILED",repo.steps(id,0,20).get(0).get("status"));
    }
    @Test void queueExpiryAndLostLeaseAreNotAutomaticallyRequeued() {
        var queued=create("queued");String id=queued.get("id").toString();jdbc.update("UPDATE agent_investigation_run SET queue_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",id);repo.recover("T001");assertEquals("QUEUE_TIMEOUT",repo.find(id).get("stop_reason"));
        create("running");var run=repo.claim("T001");id=run.get("id").toString();jdbc.update("UPDATE agent_investigation_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",id);
        assertFalse(repo.renew(id,run.get("claim_token").toString()));repo.recover("T001");assertEquals("INTERRUPTED",repo.find(id).get("status"));assertNull(repo.claim("T001"));
    }
    @Test void unfinishedStepDoesNotAdvanceCursorPastCompletionUpdate() {
        create("key");var run=repo.claim("T001");String id=run.get("id").toString(),token=run.get("claim_token").toString();
        int first=repo.startStep(id,token,"MODEL",null,null,Map.of());int second=repo.startStep(id,token,"CONTROL",null,null,Map.of());repo.endStep(id,token,second,"SUCCEEDED",Map.of(),null,1,null);
        assertTrue(repo.steps(id,0,20).isEmpty());repo.endStep(id,token,first,"SUCCEEDED",Map.of(),null,1,null);assertEquals(2,repo.steps(id,0,20).size());
    }
    @Test void currentSnapshotKeepsLargeIdsAndDetectsChangedFactsWithoutBusinessWrites() {
        var selected=facts.select(ACTOR,"plan",null);assertEquals(List.of("9007199254740993"),selected);
        var source=facts.snapshot(ACTOR,"plan",selected,false);assertNotNull(source.get("fingerprint"));assertEquals(source.get("fingerprint"),facts.snapshot(ACTOR,"plan",selected,false).get("fingerprint"));
        jdbc.update("UPDATE dispatch_plan_item SET status='FAILED',error_code='BUSINESS_REJECTED' WHERE plan_id='plan'");assertNotEquals(source.get("fingerprint"),facts.snapshot(ACTOR,"plan",selected,false).get("fingerprint"));
        assertEquals("REVIEW_REQUIRED",jdbc.queryForObject("SELECT status FROM dispatch_plan WHERE id='plan'",String.class));
    }
    @Test void childTenantMustMatchRunAndCleanupRemovesChildrenFirst() {
        create("key");var run=repo.claim("T001");String id=run.get("id").toString(),token=run.get("claim_token").toString();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> jdbc.update("INSERT INTO agent_investigation_step(run_id,tenant_id,seq,kind,status,started_at) VALUES (?,'OTHER',1,'MODEL','STARTED',UTC_TIMESTAMP(3))",id));
        repo.evidence(id,token,"ITEM_SNAPSHOT",List.of("I1"),Map.of(),Map.of("status","UNKNOWN"),false);repo.cancel(id);repo.delete(id);assertThrows(ApiException.class,() -> repo.find(id));
    }
    /** 上下文控制步骤与业务证据共用运行边界；取消后禁止迟到回写，业务状态保持不变。 */
    @Test void contextStepAndBusinessEvidencePersistWithinRun() {
        create("context");var run=repo.claim("T001");String id=run.get("id").toString(),token=run.get("claim_token").toString();
        String ref=repo.evidence(id,token,"ITEM_SNAPSHOT",List.of("I1"),Map.of("executionVersion",1),Map.of("items",List.of(Map.of("itemRef","I1","status","UNKNOWN"))),false);
        int seq=repo.startStep(id,token,"CONTROL",null,"CONTEXT_COMPACT",Map.of("beforeBytes",30000));
        repo.endStep(id,token,seq,"SUCCEEDED",Map.of("afterBytes",10000,"evidenceIds",List.of(ref)),null,0,null);
        assertEquals(ref,repo.evidence(id,ref).get("evidence_ref"));
        var steps=repo.steps(id,0,20);assertEquals(1,steps.size());assertEquals("CONTEXT_COMPACT",steps.get(0).get("tool_name"));
        assertTrue(steps.get(0).get("result_json").toString().contains(ref));
        assertEquals("CANCELLED",repo.cancel(id).get("status"));
        assertThrows(InvestigationFailure.class,() -> repo.startStep(id,token,"CONTROL",null,"CONTEXT_COMPACT",Map.of()));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM dispatch_plan_item WHERE plan_id='plan'",String.class));
    }
    @Test void taskCursorDoesNotRepeatAfterNewTaskInsertionAndRejectsOtherScope() {
        var first=create("first");repo.cancel(first.get("id").toString());
        var second=create("second");repo.cancel(second.get("id").toString());
        jdbc.update("UPDATE agent_investigation_run SET created_at=TIMESTAMPADD(SECOND,-2,UTC_TIMESTAMP(3)) WHERE id=?",first.get("id"));
        jdbc.update("UPDATE agent_investigation_run SET created_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",second.get("id"));
        var page=repo.list(ACTOR,"plan","",1);String anchor=page.get(0).get("id").toString();
        create("new");var following=repo.list(ACTOR,"plan",anchor,20);
        assertTrue(following.stream().noneMatch(row -> anchor.equals(row.get("id"))));assertEquals(1,following.size());
        assertThrows(ApiException.class,() -> repo.list(ACTOR,"other-plan",anchor,1));
    }
}
