package com.example.report.operations;

import com.example.report.catalog.*;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.*;
import com.example.report.entity.*;
import com.example.report.memory.RedisChatMemoryRepository;
import com.example.report.permission.*;
import com.example.report.rule.RuleService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring wiring, Flyway and SQL in a UUID database; never touches report_demo. */
@EnabledIfEnvironmentVariable(named="P2_IT",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"demo.reset-on-startup=true","agent.retention-sweep-ms=3600000","spring.data.redis.database=15"})
@ActiveProfiles("mock")
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperationsIntegrationTest {
    static final String SCHEMA="p2_it_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("server.port",()->"true".equals(System.getenv("P2_UI"))?8080:0);
        p.add("spring.datasource.url",()->"jdbc:mysql://"+System.getenv().getOrDefault("TRACE_DB_HOST","127.0.0.1")+":"+System.getenv().getOrDefault("TRACE_DB_PORT","3306")+"/"+SCHEMA+"?createDatabaseIfNotExist=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("TRACE_DB_USER","root"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("TRACE_DB_PASSWORD",""));
    }
    @Autowired OperationsPolicy policies;
    @Autowired OperationsWorkbench workbench;
    @Autowired BusinessMetrics metrics;
    @Autowired OperationsAudit audit;
    @Autowired CatalogRevisions revisions;
    @Autowired ReportCatalogAdminService catalogAdmin;
    @Autowired ReportCatalogService catalog;
    @Autowired ReportCatalog rawCatalog;
    @Autowired PermissionService permissions;
    @Autowired ConversationService conversations;
    @Autowired DataRetentionService retention;
    @Autowired RedisChatMemoryRepository memory;
    @Autowired PreviewService previews;
    @Autowired PlanService plans;
    @Autowired DispatchService dispatch;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Autowired ResolverEvaluationMonitor evaluationMonitor;
    @Autowired DispatchGateway gateway;
    static JdbcTemplate cleanup;
    CurrentUser admin(){return permissions.resolve("admin");}
    CurrentUser user(){return permissions.resolve("user1");}
    static final String REPORT="rpt-sales-order";
    @BeforeEach void rememberCleanup(){cleanup=jdbc;}
    @AfterAll static void dropOwnDatabase(){
        if(cleanup!=null&&SCHEMA.matches("p2_it_[a-f0-9]{32}")){
            assertEquals(SCHEMA,cleanup.queryForObject("SELECT DATABASE()",String.class));
            cleanup.execute("DROP DATABASE `"+SCHEMA+"`");
        }
    }
    @Test @Order(1) void fullApplicationStartsAndAdminApisRejectRegularUsers() {
        HttpHeaders headers=new HttpHeaders();headers.set("X-User-Id","user1");
        var denied=rest.exchange("/api/operations/workbench",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertTrue(denied.getStatusCode().is4xxClientError() || denied.getBody().contains("403"),denied.toString());
        headers.set("X-User-Id","admin");
        var accepted=rest.exchange("/api/operations/metrics",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertEquals(HttpStatus.OK,accepted.getStatusCode());assertTrue(accepted.getBody().contains("samples"));
    }
    @Test @Order(2) void policyCasRollbackAndCatalogGateInvalidateOldPreview() {
        var preview=previews.preview(user(),null,new PreviewCommand(null,"api","销售报表",null,null,null,null)).snapshot();
        var initial=policies.get(admin().tenantId(),"catalog:"+REPORT);
        var off=policies.save(admin(),initial.key(),new OperationsPolicy.Change(initial.version(),Map.of("percent",0),"灰度关闭"));
        assertThrows(ApiException.class,()->catalog.requireDispatchable(user(),REPORT));
        assertThrows(ApiException.class,()->plans.create(user(),null,preview.preview().getId(),List.of(),"old-policy"));
        assertThrows(ApiException.class,()->policies.save(admin(),initial.key(),new OperationsPolicy.Change(0,Map.of("percent",100),"并发旧版本")));
        var restored=policies.rollback(admin(),initial.key(),0,off.version(),"回滚灰度");
        assertTrue(restored.version()>off.version());assertNotNull(catalog.requireDispatchable(user(),REPORT));
        assertThrows(ApiException.class,()->policies.save(new CurrentUser("T002","admin2","",Set.of(),Set.of("*"),true),initial.key(),new OperationsPolicy.Change(0,Map.of("percent",0),"跨租户")));
    }
    @Test @Order(3) void aliasHistoryAndRollbackRestoreDefinitionWithHigherVersion() {
        var before=rawCatalog.find(REPORT).orElseThrow();
        var form=new ReportCatalogAdminService.AliasForm();form.setAlias("测试业务说法");
        catalogAdmin.addAlias(admin(),REPORT,form);
        assertEquals(REPORT,catalog.resolve(user(),"测试业务说法").reportIds().get(0));
        var latest=rawCatalog.find(REPORT).orElseThrow();
        var restored=catalogAdmin.rollback(admin(),REPORT,before.catalogVersion(),latest.catalogVersion());
        assertTrue(restored.getCatalogVersion()>latest.catalogVersion());
        assertFalse(rawCatalog.find(REPORT).orElseThrow().activeAliases().stream().anyMatch(a->a.alias().equals("测试业务说法")));
    }
    @Test @Order(4) void metricsHaveTenantIsolationDurationsAndVersionDimensions() {
        metrics.record(user(),"RESOLVE","*","42","EXACT",System.nanoTime()-10000000);
        assertTrue(metrics.summary(admin(),7).stream().anyMatch(r->"42".equals(r.get("version"))&&((Number)r.get("p95_ms")).longValue()>=10));
        var other=new CurrentUser("T002","admin2","",Set.of(),Set.of("*"),true);
        assertTrue(metrics.summary(other,7).isEmpty());assertTrue(audit.list(other,0).isEmpty());
        assertNotNull(metrics.dispatch(admin(),7));
    }
    @Test @Order(5) void erasureRemovesMessagesAndMemoryAndPreventsLateRepopulation() {
        var c=conversations.create(user(),"mock");
        conversations.logUser(c.getId(),user().userId(),"电话13812345678，邮箱a@example.com");
        String content=jdbc.queryForObject("SELECT content FROM agent_message WHERE conversation_id=?",String.class,c.getId());
        assertFalse(content.contains("13812345678"));
        memory.saveAll(c.getId(),List.of(new UserMessage("电话13812345678")));
        retention.request(user(),c.getId(),"用户删除");retention.erase(c.getId());
        assertTrue(memory.findByConversationId(c.getId()).isEmpty());
        memory.saveAll(c.getId(),List.of(new UserMessage("late")));
        assertTrue(memory.findByConversationId(c.getId()).isEmpty());
        conversations.logUser(c.getId(),user().userId(),"late");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE conversation_id=?",Integer.class,c.getId()));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM conversation_erasure WHERE conversation_id=?",String.class,c.getId()));
    }
    @Test @Order(6) void unresolvedPlanHoldsErasureAndAppearsInWorkbench() {
        var c=conversations.create(user(),"mock");conversations.logUser(c.getId(),user().userId(),"查询销售");
        var p=previews.preview(user(),c.getId(),new PreviewCommand(null,"api","销售报表",null,null,null,null)).snapshot();
        var plan=plans.create(user(),c.getId(),p.preview().getId(),List.of(),"workbench").plan();
        jdbc.update("UPDATE dispatch_plan SET status='REVIEW_REQUIRED' WHERE id=?",plan.getId());
        retention.request(user(),c.getId(),"删除但保留核对依据");retention.erase(c.getId());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM conversation_erasure WHERE conversation_id=?",String.class,c.getId()));
        assertTrue(workbench.list(admin(),null).rows().stream().anyMatch(r->plan.getId().equals(r.get("id"))));
        assertThrows(ApiException.class,()->workbench.act(admin(),plan.getId(),"confirm","不得直接确认"));
        assertThrows(ApiException.class,()->workbench.list(user(),null));
        var limited=new CurrentUser("T001","limited","",Set.of("B"),Set.of("*"),true);
        assertTrue(workbench.list(limited,null).rows().isEmpty());
    }
    @Test @Order(7) void retentionSweepExecutesRealSqlWithoutTouchingUnresolvedWork() {
        var p=previews.preview(user(),null,new PreviewCommand(null,"api","费用报表",null,null,null,null)).snapshot();
        jdbc.update("UPDATE dispatch_preview SET expires_at=TIMESTAMPADD(DAY,-400,NOW()) WHERE id=?",p.preview().getId());
        retention.sweep();
        assertEquals("DATA_PURGED",jdbc.queryForObject("SELECT status_reason FROM dispatch_preview WHERE id=?",String.class,p.preview().getId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_preview_item WHERE preview_id=?",Integer.class,p.preview().getId()));
    }
    @Test @Order(8) void twoConcurrentPolicyWritersCannotLoseAnUpdate() throws Exception {
        var before=policies.get(admin().tenantId(),"resolver");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++) jobs.add(pool.submit(()->{
                start.await();
                try { policies.save(admin(),"resolver",new OperationsPolicy.Change(before.version(),Map.of("percent",20,"fuzzyThreshold",0.6,"ambiguityMargin",0.15),"并发验证"));return true; }
                catch(ApiException conflict){assertEquals(409,conflict.getCode());return false;}
            }));
            start.countDown();int successes=0;
            for(var job:jobs) if(job.get(15,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(before.version()+1,policies.get(admin().tenantId(),"resolver").version());
        } finally { pool.shutdownNow(); }
    }
    @Test @Order(9) void rollbackRestoresNullOwnerAndEffectiveDates() {
        var before=rawCatalog.find(REPORT).orElseThrow();
        var form=new ReportCatalogAdminService.DefinitionForm();form.setOwnerUserId("temporary-owner");form.setEffectiveTo(java.time.LocalDateTime.now().plusDays(30));form.setExpectedVersion(before.catalogVersion());
        var updated=catalogAdmin.update(admin(),REPORT,form);
        catalogAdmin.rollback(admin(),REPORT,before.catalogVersion(),updated.getCatalogVersion());
        var restored=rawCatalog.find(REPORT).orElseThrow();assertEquals(before.ownerUserId(),restored.ownerUserId());assertEquals(before.effectiveTo(),restored.effectiveTo());
        assertThrows(ApiException.class,()->catalogAdmin.update(admin(),REPORT,form));
    }
    @Test @Order(10) void automaticEvaluationIsVersionedDeduplicatedAndTenantEditable() {
        var result=evaluationMonitor.run("T001");assertEquals(result.total(),result.passed());
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM resolver_evaluation_run WHERE tenant_id='T001'",Integer.class);
        evaluationMonitor.run("T001");assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM resolver_evaluation_run WHERE tenant_id='T001'",Integer.class));
        var corpus=policies.get("T001","evaluation");
        var sample=Map.of("query","销售报表","expected","EXACT","reports",List.of(REPORT));
        policies.save(admin(),"evaluation",new OperationsPolicy.Change(corpus.version(),Map.of("samples",List.of(sample)),"维护租户真实样本"));
        var changed=evaluationMonitor.run("T001");assertEquals(1,changed.total());assertEquals(1,changed.passed());
        assertEquals(before+1,jdbc.queryForObject("SELECT COUNT(*) FROM resolver_evaluation_run WHERE tenant_id='T001'",Integer.class));
    }
    @Test @Order(11) void operatorCanRetryOnlyDefiniteFailuresUsingOriginalRequestIds() {
        var c=conversations.create(user(),"mock");
        var preview=previews.preview(user(),c.getId(),new PreviewCommand(null,"api","费用报表",null,null,null,null)).snapshot();
        var plan=plans.create(user(),c.getId(),preview.preview().getId(),List.of(),"operator-retry").plan();
        var failing=org.mockito.Mockito.mock(DispatchGateway.class);
        org.mockito.Mockito.when(failing.dispatch(org.mockito.ArgumentMatchers.any())).thenReturn(DispatchGateway.Outcome.fail("TEMPORARY","暂时失败"));
        org.springframework.test.util.ReflectionTestUtils.setField(dispatch,"gateway",failing);
        try { dispatch.confirm(user(),plan.getId()); } finally { org.springframework.test.util.ReflectionTestUtils.setField(dispatch,"gateway",gateway); }
        var ids=jdbc.queryForList("SELECT external_request_id FROM dispatch_plan_item WHERE plan_id=? ORDER BY seq",String.class,plan.getId());
        retention.request(user(),c.getId(),"边恢复边清理");
        workbench.act(admin(),plan.getId(),"retry-failed","运营重试");
        assertEquals(ids,jdbc.queryForList("SELECT external_request_id FROM dispatch_plan_item WHERE plan_id=? ORDER BY seq",String.class,plan.getId()));
        assertEquals(plan.getItemCount(),jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan_item WHERE plan_id=? AND status='SUCCESS'",Integer.class,plan.getId()));
        assertThrows(ApiException.class,()->workbench.act(admin(),plan.getId(),"retry-failed","重复操作被拒绝"));
        retention.erase(c.getId());
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM conversation_erasure WHERE conversation_id=?",String.class,c.getId()));
    }
    @Test @Order(12) void closingKnownFailureNeverDispatchesAndCannotCloseUnknownWork() {
        var preview=previews.preview(user(),null,new PreviewCommand(null,"api","应收报表",null,null,null,null)).snapshot();
        var plan=plans.create(user(),null,preview.preview().getId(),List.of(),"operator-close").plan();
        jdbc.update("UPDATE dispatch_plan SET status='REVIEW_REQUIRED' WHERE id=?",plan.getId());
        assertThrows(ApiException.class,()->workbench.act(admin(),plan.getId(),"close","禁止猜测"));
        jdbc.update("UPDATE dispatch_plan SET status='EXECUTED',failed_count=item_count WHERE id=?",plan.getId());
        jdbc.update("UPDATE dispatch_plan_item SET status='FAILED' WHERE plan_id=?",plan.getId());
        int requests=jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_gateway_request",Integer.class);
        workbench.act(admin(),plan.getId(),"close","业务已撤销，无需重试");
        assertEquals(requests,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_gateway_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan_item WHERE plan_id=? AND status='FAILED'",Integer.class,plan.getId()));
        assertFalse(workbench.list(admin(),null).rows().stream().anyMatch(r->plan.getId().equals(r.get("id"))));
    }
    @Test @Order(13) void globalRatesRemainAvailableWhenDetailGroupsReachTheLimit() {
        var batch=new ArrayList<Object[]>();
        for(int i=0;i<1001;i++) batch.add(new Object[]{"T001","PREVIEW_REPORT","scale-"+i,"1","OK",1});
        jdbc.batchUpdate("INSERT INTO business_metric(tenant_id,operation,report_id,version,outcome,duration_ms,created_at) VALUES (?,?,?,?,?,?,NOW())",batch);
        assertEquals(1000,metrics.summary(admin(),7).size());
        assertTrue(JsonUtil.toJson(metrics.overview(admin(),7)).contains("RESOLVE"));
    }
    @Test @Order(14) void retentionAlsoFindsTenantsWithoutConversationsOrCustomPolicies() {
        jdbc.update("INSERT INTO business_metric(tenant_id,operation,report_id,version,outcome,duration_ms,created_at) VALUES ('headless','RESOLVE','*','1','NONE',1,TIMESTAMPADD(DAY,-100,NOW()))");
        retention.sweep();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_metric WHERE tenant_id='headless'",Integer.class));
    }
    /** Optional manual browser session, still confined to the disposable database. */
    @Test @Order(99) @EnabledIfEnvironmentVariable(named="P2_UI",matches="true")
    void browserAcceptanceSession() throws Exception {
        java.nio.file.Path stop=java.nio.file.Path.of("target/p2-ui-stop");
        java.nio.file.Files.deleteIfExists(stop);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/p2-ui-ready"),"http://localhost:8080");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(20);
        while(!java.nio.file.Files.exists(stop)&&System.nanoTime()<deadline) Thread.sleep(250);
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/p2-ui-ready"));
    }
}
