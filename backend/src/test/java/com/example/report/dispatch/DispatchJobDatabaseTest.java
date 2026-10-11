package com.example.report.dispatch;

import com.example.report.catalog.*;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.operations.OperationsWorkbench;
import com.example.report.permission.*;
import com.example.report.support.TestCatalog;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离数据库验证任务队列、条件更新、租约及历史分页 SQL；业务结果使用合成事实，不作为真实模型或外部业务验收。 */
@EnabledIfEnvironmentVariable(named="TRACE_IT",matches="true")
class DispatchJobDatabaseTest {
    static String schema;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    DispatchService dispatch;
    PlanService plans;
    PlanRepository repository;
    DispatchJobService first,second;
    CurrentUser user=TestCatalog.USER1;
    static String env(String key,String fallback) { return System.getenv().getOrDefault(key,fallback); }
    @BeforeAll static void database() {
        schema="dispatch_job_it_"+UUID.randomUUID().toString().replace("-","");
        var ds=new DriverManagerDataSource("jdbc:mysql://"+env("TRACE_DB_HOST","127.0.0.1")+":"+env("TRACE_DB_PORT","3306")+"/"+schema+"?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",env("TRACE_DB_USER","root"),env("TRACE_DB_PASSWORD",""));
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Flyway.configure().dataSource(ds).load().migrate();
    }
    @AfterAll static void drop() {
        if(schema.matches("dispatch_job_it_[a-f0-9]{32}") && schema.equals(jdbc.queryForObject("SELECT DATABASE()",String.class))) jdbc.execute("DROP DATABASE `"+schema+"`");
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM dispatch_job");
        var permissions=mock(PermissionService.class);when(permissions.tenantId()).thenReturn(user.tenantId());when(permissions.resolve(user.userId())).thenReturn(user);
        plans=mock(PlanService.class);repository=mock(PlanRepository.class);dispatch=mock(DispatchService.class);
        var catalog=mock(ReportCatalogService.class);var report=new TestCatalog().get(TestCatalog.SALES);
        when(catalog.requireVisible(eq(user),anyString())).thenReturn(report);
        when(catalog.requireDispatchable(eq(user),anyString())).thenReturn(report);
        when(catalog.find(report.reportId())).thenReturn(Optional.of(report));
        var workbench=mock(OperationsWorkbench.class);
        first=new DispatchJobService(jdbc,tx,permissions,plans,repository,dispatch,catalog,workbench);
        second=new DispatchJobService(jdbc,tx,permissions,plans,repository,dispatch,catalog,workbench);
        when(dispatch.dispatchDirect(eq(user),anyString(),anyList())).thenReturn(new DispatchService.ManualResult(1,1,0,0,List.of()));
    }
    @AfterEach void close() { first.close();second.close(); }
    DispatchJobService.Request direct(String id) { return new DispatchJobService.Request(null,"DIRECT",TestCatalog.SALES,List.of(id),null); }
    String key() { return UUID.randomUUID().toString().replace("-",""); }
    DispatchJobService.Job finished(String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            var job=first.get(user,id);
            if(Set.of("SUCCEEDED","FAILED").contains(job.status())) return job;
            Thread.sleep(10);
        }
        fail("job did not finish");return null;
    }
    @Test void responseLossReplaysExactTaskAndRejectsChangedPayload() {
        String key=key();var original=first.submit(user,direct("1"),key);
        assertEquals(original.id(),second.submit(user,direct("1"),key).id());
        assertThrows(ApiException.class,()->second.submit(user,direct("2"),key));
        assertThrows(ApiException.class,()->first.get(TestCatalog.USER3,original.id()));
        verifyNoInteractions(dispatch);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_job",Integer.class));
    }
    @Test void foreignTenantTasksAreNeitherClaimedNorRecovered() {
        var queued=first.submit(user,direct("1"),key());
        var expired=first.submit(user,direct("2"),key());
        var running=first.submit(user,direct("3"),key());
        jdbc.update("UPDATE dispatch_job SET tenant_id='T002' WHERE id IN (?,?,?)",queued.id(),expired.id(),running.id());
        jdbc.update("UPDATE dispatch_job SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",expired.id());
        jdbc.update("UPDATE dispatch_job SET status='RUNNING',claim_token='other-tenant',lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",running.id());
        first.poll();second.poll();
        assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM dispatch_job WHERE id=?",String.class,queued.id()));
        assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM dispatch_job WHERE id=?",String.class,expired.id()));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM dispatch_job WHERE id=?",String.class,running.id()));
        verifyNoInteractions(dispatch);
    }
    @Test void previewRecoveryOnlyChangesTheConfiguredTenant() {
        String local=key(),foreign=key();
        for(var entry:Map.of(local,"T001",foreign,"T002").entrySet())
            jdbc.update("INSERT INTO dispatch_preview_job(id,tenant_id,user_id,conversation_id,request_version,status,stage,created_at,updated_at) VALUES(?,?,?,'recovery',1,'RUNNING','SCANNING',NOW(),TIMESTAMPADD(MINUTE,-5,NOW()))",entry.getKey(),entry.getValue(),user.userId());
        var preview=new PreviewJobService(jdbc,mock(PreviewService.class),mock(com.example.report.agent.ConversationCards.class),
                mock(com.example.report.config.ResourceQuotaService.class),new PermissionService());
        try {
            preview.recoverOrphans();
            assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM dispatch_preview_job WHERE id=?",String.class,local));
            assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM dispatch_preview_job WHERE id=?",String.class,foreign));
        } finally {preview.shutdown();jdbc.update("DELETE FROM dispatch_preview_job WHERE id IN (?,?)",local,foreign);}
    }
    @Test void twoInstancesClaimOnceAndPersistResult() throws Exception {
        var job=first.submit(user,direct("1"),key());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(first::poll);var b=pool.submit(second::poll);a.get();b.get();
            assertEquals("SUCCEEDED",finished(job.id()).status());
            verify(dispatch,times(1)).dispatchDirect(eq(user),eq(TestCatalog.SALES),eq(List.of("1")));
            assertEquals(1,((Map<?,?>)first.get(user,job.id()).result()).get("successCount"));
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredRunningTaskNeverAutomaticallyResends() {
        var job=first.submit(user,direct("1"),key());
        jdbc.update("UPDATE dispatch_job SET status='RUNNING',claim_token='lost-worker',lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",job.id());
        first.recover();second.poll();
        assertEquals("FAILED",first.get(user,job.id()).status());
        assertTrue(first.get(user,job.id()).message().contains("不会自动重发"));
        verifyNoInteractions(dispatch);
    }
    @Test void staleQueuedCommandExpiresInsteadOfExecutingAfterRestart() {
        var job=first.submit(user,direct("1"),key());
        jdbc.update("UPDATE dispatch_job SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",job.id());
        second.poll();assertEquals("FAILED",first.get(user,job.id()).status());verifyNoInteractions(dispatch);
    }
    @Test void submissionReturnsBeforeSlowBusinessAndFreshServiceCanResumeQueue() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(dispatch.dispatchDirect(eq(user),anyString(),anyList())).thenAnswer(call->{ entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return new DispatchService.ManualResult(1,1,0,0,List.of()); });
        var job=first.submit(user,direct("1"),key());
        assertEquals("QUEUED",job.status());first.close();second.poll();
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals("RUNNING",second.get(user,job.id()).status());
        } finally { release.countDown(); }
        assertEquals("SUCCEEDED",finished(job.id()).status());
    }
    @Test void userQueueBudgetRejectsNinthUnstartedTask() {
        for(int i=0;i<8;i++) first.submit(user,direct(Integer.toString(i)),key());
        var denied=assertThrows(ApiException.class,()->second.submit(user,direct("9"),key()));
        assertEquals(429,denied.getCode());verifyNoInteractions(dispatch);
    }
    @Test void queuedConfirmationCannotAdoptALaterPlanVersionAndSecondKeyCannotCreateAnotherActiveCommand() throws Exception {
        String planId=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO dispatch_plan(id,preview_id,tenant_id,user_id,status,item_count,idempotency_key,created_at,expires_at,updated_at) VALUES (?,?,'T001','user1','PENDING',1,?,NOW(),TIMESTAMPADD(HOUR,1,NOW()),NOW())",planId,planId,planId);
        var request=new DispatchJobService.Request(planId,"CONFIRM",null,null,null);
        var original=first.submit(user,request,key());
        var denied=assertThrows(ApiException.class,()->second.submit(user,request,key()));assertEquals(409,denied.getCode());
        var changed=new com.example.report.entity.DispatchPlan();changed.setId(planId);changed.setExecutionVersion(1L);
        when(repository.find(planId)).thenReturn(Optional.of(changed));
        second.poll();assertEquals("FAILED",finished(original.id()).status());
        assertTrue(first.get(user,original.id()).message().contains("版本已变化"));verifyNoInteractions(dispatch);
    }

    /** 仅向本测试隔离库写入任务事实，不认领、不调用业务派单。 */
    void historyJob(String id,String tenant,String owner,LocalDateTime created,String status,
                    DispatchJobService.Request request,Object result,String message) {
        jdbc.update("INSERT INTO dispatch_job(id,tenant_id,user_id,plan_id,action,idempotency_key,payload_json,trace_id,status,result_json,message,created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,tenant,owner,request.planId(),request.action(),"private-key-"+id,JsonUtil.toJson(request),"private-trace-"+id,
                status,result==null?null:JsonUtil.toJson(result),message,created,created,created.plusMinutes(10));
    }

    @Test void historyUsesCreationTimeAndIdCursorWithoutLeakingExecutionCredentials() {
        var time=LocalDateTime.of(2026,10,11,10,0);
        for(String id:List.of("f".repeat(32),"e".repeat(32),"d".repeat(32)))
            historyJob(id,user.tenantId(),user.userId(),time,"FAILED",direct("1"),null,"单据 PRIVATE-DOC 属于 B 公司");
        var page=first.history(user,null,null,1);
        assertEquals(List.of("f".repeat(32)),page.rows().stream().map(DispatchJobService.HistoryEntry::id).toList());
        assertNotNull(page.nextCursor());
        String serialized=JsonUtil.toJson(page);
        for(String hidden:List.of("private-key","private-trace","PRIVATE-DOC","payload_json","idempotencyKey","recordIds"))
            assertFalse(serialized.contains(hidden));
        jdbc.update("UPDATE dispatch_job SET updated_at=TIMESTAMPADD(DAY,1,updated_at) WHERE id=?","d".repeat(32));
        historyJob("a".repeat(32),user.tenantId(),user.userId(),time.plusSeconds(1),"FAILED",direct("2"),null,null);
        var next=second.history(user,page.nextCursor(),null,1);
        assertEquals("e".repeat(32),next.rows().get(0).id());
        var last=second.history(user,next.nextCursor(),null,1);
        assertEquals("d".repeat(32),last.rows().get(0).id());assertNull(last.nextCursor());
        assertEquals("private-key-"+"f".repeat(32),first.get(user,"f".repeat(32)).idempotencyKey(),"原单任务恢复契约保持稳定");
        verifyNoInteractions(dispatch);
    }

    @Test void historyRejectsMalformedCursorsAndRequiresResetWhenFilterChanges() {
        var time=LocalDateTime.of(2026,10,11,10,0);
        historyJob("f".repeat(32),user.tenantId(),user.userId(),time,"FAILED",direct("1"),null,null);
        historyJob("e".repeat(32),user.tenantId(),user.userId(),time,"QUEUED",direct("2"),null,null);
        var page=first.history(user,null,null,1);
        assertEquals(400,assertThrows(ApiException.class,()->first.history(user,page.nextCursor(),"FAILED",10)).getCode());
        assertThrows(ApiException.class,()->first.history(TestCatalog.USER3,page.nextCursor(),null,10));
        for(String cursor:List.of("", "!", "A", "e30", "a".repeat(1025)))
            assertThrows(ApiException.class,()->first.history(user,cursor,null,10));
        for(String status:List.of("", "failed", "UNKNOWN", "FAILED' OR 1=1"))
            assertThrows(ApiException.class,()->first.history(user,null,status,10));
        assertThrows(ApiException.class,()->first.history(user,null,null,0));
        assertThrows(ApiException.class,()->first.history(user,null,null,51));
        var filtered=first.history(user,null,"QUEUED",10);
        assertEquals(1,filtered.rows().size());assertEquals("QUEUED",filtered.rows().get(0).status());assertNull(filtered.nextCursor());
    }

    @Test void historyIsTenantAndOwnerScopedAndSkipsCurrentlyUnreadablePlans() {
        var time=LocalDateTime.of(2026,10,11,10,0);
        String deniedPlan=key(),visiblePlan=key();
        var denied=new DispatchJobService.Request(deniedPlan,"CONFIRM",null,null,null);
        var visible=new DispatchJobService.Request(visiblePlan,"CONFIRM",null,null,null);
        historyJob("f".repeat(32),"T002",user.userId(),time.plusSeconds(2),"SUCCEEDED",visible,Map.of(),null);
        historyJob("e".repeat(32),user.tenantId(),"other",time.plusSeconds(1),"SUCCEEDED",visible,Map.of(),null);
        historyJob("d".repeat(32),user.tenantId(),user.userId(),time,"SUCCEEDED",denied,Map.of(),"旧范围结果");
        historyJob("c".repeat(32),user.tenantId(),user.userId(),time,"SUCCEEDED",visible,Map.of(),null);
        doThrow(ApiException.forbidden("公司授权已撤销")).when(plans).pageOwned(user,deniedPlan,1,1);
        var plan=new com.example.report.entity.DispatchPlan();plan.setId(visiblePlan);plan.setTenantId(user.tenantId());plan.setUserId(user.userId());plan.setStatus("EXECUTED");
        when(repository.find(visiblePlan)).thenReturn(Optional.of(plan));
        var page=first.history(user,null,null,10);
        assertEquals(List.of("c".repeat(32)),page.rows().stream().map(DispatchJobService.HistoryEntry::id).toList());
        assertEquals(visiblePlan,page.rows().get(0).plans().get(0).planId());assertNull(page.nextCursor());
        assertEquals(403,assertThrows(ApiException.class,()->first.get(user,"d".repeat(32))).getCode());
        verify(repository,never()).find(deniedPlan);
    }

    @Test void boundedHistoryScanCanContinueAfterACompletelyHiddenPage() {
        var time=LocalDateTime.of(2026,10,11,10,0);
        String deniedPlan=key();var denied=new DispatchJobService.Request(deniedPlan,"CONFIRM",null,null,null);
        doThrow(ApiException.forbidden("报表授权已撤销")).when(plans).pageOwned(user,deniedPlan,1,1);
        for(int index=201;index>1;index--)
            historyJob(String.format("%032x",index),user.tenantId(),user.userId(),time,"FAILED",denied,null,null);
        historyJob(String.format("%032x",1),user.tenantId(),user.userId(),time,"FAILED",direct("1"),null,null);
        var hidden=first.history(user,null,null,20);
        assertTrue(hidden.rows().isEmpty());assertNotNull(hidden.nextCursor());
        var visible=second.history(user,hidden.nextCursor(),null,20);
        assertEquals(1,visible.rows().size());assertEquals(String.format("%032x",1),visible.rows().get(0).id());assertNull(visible.nextCursor());
    }

    @Test void directHistoryLinksEveryRetainedPlanAndNeverReturnsAPartiallyAuthorizedResult() {
        var time=LocalDateTime.of(2026,10,11,10,0);String id=key(),one=key(),two=key();
        var result=new DispatchService.ManualResult(2,1,1,0,List.of(
                new DispatchService.ManualPlan(one,"SO1","EXECUTED",0,"SUCCESS",null),
                new DispatchService.ManualPlan(two,"SO2","EXECUTED",1,"FAILED","临时失败")));
        historyJob(id,user.tenantId(),user.userId(),time,"SUCCEEDED",direct("1"),result,null);
        for(String planId:List.of(one,two)) {
            var plan=new com.example.report.entity.DispatchPlan();plan.setId(planId);plan.setTenantId(user.tenantId());plan.setUserId(user.userId());plan.setStatus("EXECUTED");
            when(repository.find(planId)).thenReturn(Optional.of(plan));
        }
        var item=first.history(user,null,null,20).rows().get(0);
        assertTrue(item.resultAvailable());assertEquals(List.of(one,two),item.plans().stream().map(DispatchJobService.HistoryPlan::planId).toList());
        assertTrue(item.plans().stream().allMatch(plan->TestCatalog.SALES.equals(plan.reportId()) && plan.conversationId()==null));
        doThrow(ApiException.forbidden("第二份清单公司权限已撤销")).when(plans).pageOwned(user,two,1,1);
        assertTrue(first.history(user,null,null,20).rows().isEmpty());
        assertEquals(403,assertThrows(ApiException.class,()->first.get(user,id)).getCode());
    }

    @Test void expiredDirectResultsExposeOnlyGenericTaskMetadata() {
        var time=LocalDateTime.of(2026,10,11,10,0);String id=key();
        historyJob(id,user.tenantId(),user.userId(),time,"SUCCEEDED",direct("PRIVATE-RECORD"),null,"B 公司 PRIVATE-DOC 共 123 条");
        var item=first.history(user,null,null,20).rows().get(0);
        assertFalse(item.resultAvailable());assertTrue(item.plans().isEmpty());assertTrue(item.message().contains("保留期"));
        var recovered=first.get(user,id);assertNull(recovered.result());assertFalse(recovered.message().contains("PRIVATE-DOC"));
        assertFalse(JsonUtil.toJson(item).contains("PRIVATE-RECORD"));
        verifyNoInteractions(plans,repository,dispatch);
        var revoked=new CurrentUser(user.tenantId(),user.userId(),"reader",Set.of("A"),Set.of("report:expense"),false);
        assertTrue(first.history(revoked,null,null,20).rows().isEmpty());
        assertEquals(404,assertThrows(ApiException.class,()->first.get(revoked,id)).getCode());
    }

    @Test void planHistoryKeepsResultAvailabilityWithoutLoadingTheRawResultAndOmitsDeletedConversationLinks() {
        var time=LocalDateTime.of(2026,10,11,10,0);String id=key(),planId=key(),conversation=key();
        jdbc.update("INSERT INTO agent_conversation(id,tenant_id,user_id,created_at,updated_at) VALUES (?,?,?,?,?)",conversation,user.tenantId(),user.userId(),time,time);
        var plan=new com.example.report.entity.DispatchPlan();plan.setId(planId);plan.setTenantId(user.tenantId());plan.setUserId(user.userId());plan.setConversationId(conversation);plan.setStatus("EXECUTED");
        when(repository.find(planId)).thenReturn(Optional.of(plan));
        historyJob(id,user.tenantId(),user.userId(),time,"SUCCEEDED",new DispatchJobService.Request(planId,"CONFIRM",null,null,null),Map.of("privateBusinessDetails","保留结果"),null);
        var entry=first.history(user,null,null,20).rows().get(0);
        assertTrue(entry.resultAvailable());assertEquals(conversation,entry.plans().get(0).conversationId());
        assertFalse(JsonUtil.toJson(entry).contains("privateBusinessDetails"));
        jdbc.update("UPDATE agent_conversation SET status='deleted' WHERE id=?",conversation);
        assertNull(first.history(user,null,null,20).rows().get(0).plans().get(0).conversationId());
    }

    @Test void malformedDirectPlanLinksFailClosedInsteadOfPublishingUnverifiedBusinessResults() {
        var time=LocalDateTime.of(2026,10,11,10,0);String id=key();
        historyJob(id,user.tenantId(),user.userId(),time,"SUCCEEDED",direct("1"),Map.of("plans",List.of(Map.of("docNo","PRIVATE-DOC"))),null);
        assertEquals(502,assertThrows(ApiException.class,()->first.history(user,null,null,20)).getCode());
        assertEquals(502,assertThrows(ApiException.class,()->first.get(user,id)).getCode());
    }
}
