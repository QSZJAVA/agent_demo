package com.example.report.dispatch;

import com.example.report.catalog.*;
import com.example.report.common.ApiException;
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
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real queue/CAS/lease SQL, synthetic service results, and a UUID database. */
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
        var permissions=mock(PermissionService.class);when(permissions.resolve(user.userId())).thenReturn(user);
        plans=mock(PlanService.class);repository=mock(PlanRepository.class);dispatch=mock(DispatchService.class);
        var catalog=mock(ReportCatalogService.class);var report=new TestCatalog().get(TestCatalog.SALES);
        when(catalog.requireVisibleByIdOrLegacyCode(eq(user),anyString())).thenReturn(report);
        when(catalog.requireDispatchable(eq(user),anyString())).thenReturn(report);
        when(catalog.find(report.reportId())).thenReturn(Optional.of(report));
        var workbench=mock(OperationsWorkbench.class);
        first=new DispatchJobService(jdbc,tx,permissions,plans,repository,dispatch,catalog,workbench);
        second=new DispatchJobService(jdbc,tx,permissions,plans,repository,dispatch,catalog,workbench);
        when(dispatch.dispatchDirect(eq(user),anyString(),anyList())).thenReturn(new DispatchService.ManualResult(1,1,0,0,List.of()));
    }
    @AfterEach void close() { first.close();second.close(); }
    DispatchJobService.Request direct(String id) { return new DispatchJobService.Request(null,"DIRECT","sales",List.of(id),null); }
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
}
