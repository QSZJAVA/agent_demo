package com.example.report.operations;

import com.example.report.catalog.*;
import com.example.report.assistant.*;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.*;
import com.example.report.entity.*;
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

/** 隔离测试库中的Spring装配、Flyway与真实SQL回归；留存合成快照仅证明数据生命周期，不代表真实模型或外部业务派单验收。 */
@EnabledIfEnvironmentVariable(named="P2_IT",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"agent.retention-sweep-ms=3600000","spring.data.redis.database=15"})
@ActiveProfiles("mock")
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperationsIntegrationTest {
    static final String SCHEMA="p2_it_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        // 输出准确隔离库名，供授权测试前后核对与清理证据归档。
        System.out.println("ISOLATED_DATABASE_TARGET="+SCHEMA);
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
    @Autowired PreviewService previews;
    @Autowired PlanService plans;
    @Autowired DispatchService dispatch;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Autowired ResolverEvaluationMonitor evaluationMonitor;
    @Autowired DispatchGateway gateway;
    @Autowired SemanticDictionaryService dictionaries;
    @Autowired AssistantQualityService quality;
    static JdbcTemplate cleanup;
    CurrentUser admin(){return permissions.resolve("admin");}
    CurrentUser user(){return permissions.resolve("user1");}
    static final String REPORT="rpt-sales-order";
    @BeforeEach void rememberCleanup(){cleanup=jdbc;}
    @AfterAll static void dropOwnDatabase(@Autowired org.springframework.context.ConfigurableApplicationContext context){
        if(cleanup!=null&&SCHEMA.matches("p2_it_[a-f0-9]{32}")){
            assertEquals(SCHEMA,cleanup.queryForObject("SELECT DATABASE()",String.class));
            // 先停止后台调度，再用独立连接清理确切测试库；Spring测试上下文由DirtiesContext关闭。
            var source=(com.zaxxer.hikari.HikariDataSource)cleanup.getDataSource();
            var drop=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(source.getJdbcUrl(),source.getUsername(),source.getPassword()));
            context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).values().forEach(scheduler->{scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(10);scheduler.shutdown();});
            drop.execute("DROP DATABASE `"+SCHEMA+"`");
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
        var preview=previews.preview(user(),null,new PreviewCommand(null, "api", "销售报表", null, null, null)).snapshot();
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
    @Test @Order(4) void unscopedSamplingIsUnavailableWhileRecordingPreservesVersionDimensions() {
        metrics.record(user(),"RESOLVE","*","42","EXACT",System.nanoTime()-10000000);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM business_metric WHERE tenant_id='T001' AND version='42' AND duration_ms>=10",Integer.class));
        var samples=metrics.summary(admin(),7);
        assertFalse(samples.available());assertFalse(samples.complete());assertNull(samples.rows());assertTrue(samples.unavailableReason().contains("公司归属"));
        var other=new CurrentUser("T002","admin2","",Set.of(),Set.of("*"),true);
        assertNull(metrics.summary(other,7).rows());assertTrue(audit.list(other,0).isEmpty());
        assertFalse(metrics.dispatch(other,7).available());assertNull(metrics.dispatch(other,7).rows());
    }
    @Test @Order(5) void erasureRemovesMessagesAndPreventsLateRepopulation() {
        var c=conversations.create(user(),"mock");
        conversations.logUser(c.getId(),user().userId(),"电话13812345678，邮箱a@example.com");
        String content=jdbc.queryForObject("SELECT content FROM agent_message WHERE conversation_id=?",String.class,c.getId());
        assertFalse(content.contains("13812345678"));
        retention.request(user(),c.getId(),"用户删除");retention.erase(c.getId());
        conversations.logUser(c.getId(),user().userId(),"late");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE conversation_id=?",Integer.class,c.getId()));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM conversation_erasure WHERE conversation_id=?",String.class,c.getId()));
    }
    @Test @Order(6) void unresolvedPlanHoldsErasureAndAppearsInWorkbench() {
        var c=conversations.create(user(),"mock");conversations.logUser(c.getId(),user().userId(),"查询销售");
        var p=previews.preview(user(),c.getId(),new PreviewCommand(null, "api", "销售报表", null, null, null)).snapshot();
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
        var p=previews.preview(user(),null,new PreviewCommand(null, "api", "费用报表", null, null, null)).snapshot();
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
        var preview=previews.preview(user(),c.getId(),new PreviewCommand(null, "api", "费用报表", null, null, null)).snapshot();
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
        var preview=previews.preview(user(),null,new PreviewCommand(null, "api", "应收报表", null, null, null)).snapshot();
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
        long before=metricItems(metrics.overview(admin(),7).dispatch(),"SUCCESS");
        var fixture=retentionFixture("T001","EXECUTED","SUCCESS",1001);
        jdbc.update("UPDATE dispatch_plan SET created_at=NOW(3) WHERE id=?",fixture.plan());
        jdbc.update("UPDATE dispatch_plan_item SET rule_version=seq WHERE plan_id=?",fixture.plan());
        var details=metrics.dispatch(admin(),7);
        assertEquals(1000,details.rows().size());assertTrue(details.available());assertTrue(details.truncated());assertFalse(details.complete());
        var overview=metrics.overview(admin(),7);
        assertTrue(overview.dispatch().complete());assertFalse(overview.dispatch().truncated());
        assertEquals(before+1001,metricItems(overview.dispatch(),"SUCCESS"));
        assertFalse(overview.requests().available());assertNull(overview.requests().rows());
    }
    @Test @Order(14) void retentionAlsoFindsTenantsWithoutConversationsOrCustomPolicies() {
        jdbc.update("INSERT INTO business_metric(tenant_id,operation,report_id,version,outcome,duration_ms,created_at) VALUES ('headless','RESOLVE','*','1','NONE',1,TIMESTAMPADD(DAY,-100,NOW()))");
        retention.sweep();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_metric WHERE tenant_id='headless'",Integer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"EXECUTED,SUCCESS","EXECUTED,SKIPPED","CANCELLED,PENDING","EXPIRED,PENDING","PENDING,PENDING"})
    @Order(15)
    void terminalRetentionClearsAllFactCopiesAndKeepsExecutionIdentity(String planState,String itemState) throws Exception {
        var fixture=retentionFixture("T001",planState,itemState,1);
        var evidence=retainedEvidence(fixture);
        var aggregate=jdbc.queryForMap("SELECT total_count,total_amount FROM dispatch_preview WHERE id=?",fixture.preview());
        assertTrue(jdbc.queryForObject("SELECT JSON_LENGTH(fields_json)>0 AND counterparty_json IS NOT NULL FROM dispatch_plan_item WHERE plan_id=?",Boolean.class,fixture.plan()));

        retention.sweep();

        assertEquals("DATA_PURGED",purgeState(fixture));
        assertEquals(0,jdbc.queryForObject("SELECT JSON_LENGTH(exclude_json) FROM dispatch_plan WHERE id=?",Integer.class,fixture.plan()));
        assertEquals(aggregate,jdbc.queryForMap("SELECT total_count,total_amount FROM dispatch_preview WHERE id=?",fixture.preview()),"保留汇总审计元数据，不能伪造为零金额或零条数");
        assertEquals(evidence,retainedEvidence(fixture),"终态、请求号、尝试次数、业务身份和版本不能因清理改变");
        var row=plans.pageOwned(user(),fixture.plan(),1,20).get(0);
        assertNull(row.getDocNo());assertNull(row.getLabel());assertNull(row.getAmount());assertNull(row.getBizDate());assertNull(row.getErrorMessage());
        assertNull(row.getCounterpartyJson());assertTrue(JsonUtil.MAPPER.readTree(row.getFieldsJson()).isEmpty());
        assertEquals("PENDING".equals(planState)?"EXPIRED":planState,jdbc.queryForObject("SELECT status FROM dispatch_plan WHERE id=?",String.class,fixture.plan()));
        retention.sweep();
        assertEquals(evidence,retainedEvidence(fixture),"重复清理必须保持原请求的防重与执行证据");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"EXECUTING,PENDING","EXECUTING,UNKNOWN","REVIEW_REQUIRED,UNKNOWN","REVIEW_REQUIRED,SUCCESS","EXECUTED,FAILED","CANCELLED,UNKNOWN","EXPIRED,FAILED"})
    @Order(16)
    void unresolvedRetentionPreservesEveryFactUntilRecoveryFinishes(String planState,String itemState) {
        var fixture=retentionFixture("T001",planState,itemState,1);
        var before=jdbc.queryForList("SELECT * FROM dispatch_plan_item WHERE plan_id=?",fixture.plan());
        String excluded=jdbc.queryForObject("SELECT exclude_json FROM dispatch_plan WHERE id=?",String.class,fixture.plan());
        retention.sweep();
        assertNotEquals("DATA_PURGED",purgeState(fixture));
        assertEquals(before,jdbc.queryForList("SELECT * FROM dispatch_plan_item WHERE plan_id=?",fixture.plan()),"有失败、结果未知或仍在执行时不得删除任何恢复事实");
        assertEquals(excluded,jdbc.queryForObject("SELECT exclude_json FROM dispatch_plan WHERE id=?",String.class,fixture.plan()));
    }

    @Test @Order(17) void largeTerminalPlanIsPurgedInResumableBatchesBeforeCompletionIsMarked() {
        var fixture=retentionFixture("T001","EXECUTED","SUCCESS",501);
        var evidence=retainedEvidence(fixture);
        retention.sweep();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan_item WHERE plan_id=? AND (JSON_LENGTH(fields_json)>0 OR counterparty_json IS NOT NULL)",Integer.class,fixture.plan()));
        assertNotEquals("DATA_PURGED",purgeState(fixture),"仍有一个结构化事实副本时不能结束清理");
        assertEquals(0,jdbc.queryForObject("SELECT JSON_LENGTH(exclude_json) FROM dispatch_plan WHERE id=?",Integer.class,fixture.plan()));
        retention.sweep();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan_item WHERE plan_id=? AND (JSON_LENGTH(fields_json)>0 OR counterparty_json IS NOT NULL)",Integer.class,fixture.plan()));
        assertEquals("DATA_PURGED",purgeState(fixture));
        assertEquals(evidence,retainedEvidence(fixture));
    }

    @Test @Order(18) void retentionUsesEachTenantsOwnPolicyWithoutClearingAnotherTenantsFactsEarly() {
        String tenant="retention-"+UUID.randomUUID().toString().replace("-","");
        var actor=new CurrentUser(tenant,"retention-admin","留存测试管理员",Set.of("A"),Set.of("*"),true);
        policies.save(actor,"retention",new OperationsPolicy.Change(0,Map.of("conversationDays",1000,"resultDays",1000,"metricDays",90,"auditDays",365),"核对租户保留边界"));
        var due=retentionFixture("T001","EXECUTED","SUCCESS",1);
        var notDue=retentionFixture(tenant,"EXECUTED","SUCCESS",1);
        var before=jdbc.queryForList("SELECT * FROM dispatch_plan_item WHERE plan_id=?",notDue.plan());
        retention.sweep();
        assertEquals("DATA_PURGED",purgeState(due));
        assertNotEquals("DATA_PURGED",purgeState(notDue));
        assertEquals(before,jdbc.queryForList("SELECT * FROM dispatch_plan_item WHERE plan_id=?",notDue.plan()));
    }

    @Test @Order(19) void metricDetailsAndTotalsShareCurrentCompanyReportAndTenantPermissions() {
        var limited=new CurrentUser("T001","limited-metrics","受限运营人员",Set.of("A"),Set.of("report:sales"),true);
        var before=metrics.overview(limited,7).dispatch();
        metricFixture("T001",REPORT,"A","SUCCESS",1);
        var companyDenied=metricFixture("T001",REPORT,"B","FAILED",2);
        var reportDenied=metricFixture("T001","rpt-ar-invoice","A","SKIPPED",3);
        metricFixture("T002",REPORT,"A","UNKNOWN",4);
        jdbc.update("UPDATE dispatch_plan_item SET catalog_version=987654321 WHERE plan_id IN (?,?)",companyDenied.plan(),reportDenied.plan());
        metrics.record(admin(),"RESOLVE","rpt-ar-invoice","unscoped-private-version","NONE",System.nanoTime());

        var snapshot=metrics.read(limited,7);
        assertEquals(List.of("A"),snapshot.scope().companyCodes());assertEquals(List.of(REPORT),snapshot.scope().reportIds());
        for(String status:List.of("SUCCESS","FAILED","SKIPPED","UNKNOWN","PENDING")) {
            long expected=metricItems(before,status)+("SUCCESS".equals(status)?1:0);
            assertEquals(expected,metricItems(snapshot.overview().dispatch(),status),status+"概览不能包含范围外条目");
            assertEquals(expected,metricItems(metrics.overview(limited,7).dispatch(),status),status+"独立概览路径采用相同权限");
        }
        assertTrue(snapshot.overview().dispatch().complete());assertEquals("CURRENT_AUTHORIZATION",snapshot.overview().dispatch().scope());
        assertTrue(snapshot.dispatch().rows().stream().allMatch(row->REPORT.equals(row.get("report_id"))));
        assertEquals(snapshot.dispatch(),metrics.dispatch(limited,7));
        assertFalse(snapshot.samples().available());assertNull(snapshot.samples().rows());assertFalse(snapshot.overview().requests().available());
        assertEquals(snapshot.samples(),metrics.summary(limited,7));
        String body=JsonUtil.toJson(snapshot);
        assertFalse(body.contains("rpt-ar-invoice"));assertFalse(body.contains("987654321"));assertFalse(body.contains("unscoped-private-version"));
    }

    @Test @Order(20) void missingMetricPermissionsAreUnavailableRatherThanReportedAsZero() {
        for(var actor:List.of(new CurrentUser("T001","no-company","",Set.of(),Set.of("report:sales"),true),
                new CurrentUser("T001","no-report","",Set.of("A"),Set.of(),true))) {
            var snapshot=metrics.read(actor,7);
            for(var series:List.of(snapshot.samples(),snapshot.dispatch(),snapshot.overview().requests(),snapshot.overview().dispatch(),metrics.dispatch(actor,7))) {
                assertFalse(series.available());assertFalse(series.complete());assertFalse(series.truncated());assertNull(series.rows());assertNotNull(series.unavailableReason());
            }
        }
        var authorizedEmpty=new CurrentUser("T001","empty-company","",Set.of("AUTHORIZED_EMPTY"),Set.of("report:sales"),true);
        var empty=metrics.read(authorizedEmpty,7);
        assertTrue(empty.dispatch().available());assertTrue(empty.dispatch().complete());assertTrue(empty.dispatch().rows().isEmpty());
        assertTrue(empty.overview().dispatch().available());assertTrue(empty.overview().dispatch().rows().isEmpty());
    }

    @Test @Order(21) void allMetricEntryPointsRequireAdministrativeIdentityAndBoundedTimeWindow() {
        assertThrows(ApiException.class,()->metrics.read(user(),7));assertThrows(ApiException.class,()->metrics.summary(user(),7));
        assertThrows(ApiException.class,()->metrics.dispatch(user(),7));assertThrows(ApiException.class,()->metrics.overview(user(),7));
        assertThrows(ApiException.class,()->metrics.read(admin(),0));assertThrows(ApiException.class,()->metrics.summary(admin(),91));
        assertThrows(ApiException.class,()->metrics.dispatch(admin(),0));assertThrows(ApiException.class,()->metrics.overview(admin(),91));
    }

    @Test @Order(22) void metricHttpResponseDeclaresUnavailableSamplingAndAuthorizedCompleteTotals() throws Exception {
        var headers=new HttpHeaders();headers.set("X-User-Id","admin");
        var response=rest.exchange("/api/operations/metrics",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        var body=JsonUtil.MAPPER.readTree(response.getBody()).path("data");
        assertFalse(body.path("samples").path("available").asBoolean());assertTrue(body.path("samples").path("rows").isNull());
        assertTrue(body.path("samples").path("unavailableReason").asText().contains("公司归属"));
        assertTrue(body.path("overview").path("dispatch").path("complete").asBoolean());
        assertEquals("CURRENT_AUTHORIZATION",body.path("overview").path("dispatch").path("scope").asText());
        assertFalse(response.getBody().contains("unscoped-private-version"));
    }

    @Test @Order(23) void operatorPlanReadIsBoundedScopedAndDoesNotChangeExpiredBusinessState() throws Exception {
        var fixture=retentionFixture("T001","PENDING","PENDING",501);
        assertThrows(ApiException.class,()->plans.getOwned(admin(),fixture.plan()),"普通清单入口仍须由原操作者读取");
        var read=workbench.readPlan(admin(),fixture.plan());
        assertEquals(fixture.plan(),read.planId());assertEquals("EXPIRED",read.status());assertEquals(501,read.count());assertEquals(50,read.records().size());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM dispatch_plan WHERE id=?",String.class,fixture.plan()),"只读摘要不得执行或持久化过期转换");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM operations_audit WHERE action='WORKBENCH_PLAN_READ' AND resource_id=? AND actor_id='admin'",Integer.class,fixture.plan()));
        for(var denied:List.of(user(),new CurrentUser("T002","other-admin","",Set.of("A"),Set.of("*"),true),
                new CurrentUser("T001","company-limited","",Set.of("B"),Set.of("*"),true),
                new CurrentUser("T001","report-limited","",Set.of("A"),Set.of("report:receivable"),true)))
            assertThrows(ApiException.class,()->workbench.readPlan(denied,fixture.plan()));
        var headers=new HttpHeaders();headers.set("X-User-Id","admin");
        var response=rest.exchange("/api/operations/workbench/"+fixture.plan(),HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        var body=JsonUtil.MAPPER.readTree(response.getBody()).path("data");
        assertEquals(fixture.plan(),body.path("planId").asText());assertEquals(50,body.path("records").size());assertEquals(501,body.path("count").asInt());
    }

    @Test @Order(24) void metricWindowStaysFixedWhenDatabaseTimeAdvancesBetweenDetailsAndTotals() {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(ignored -> {
            long fixed=java.time.Instant.parse("2026-10-11T00:00:00Z").getEpochSecond();
            try {
                jdbc.execute("SET timestamp="+fixed);
                var observed=jdbc.queryForObject("SELECT NOW(3)",java.time.LocalDateTime.class);
                var included=metricFixture("T001",REPORT,"WINDOW_TEST","SUCCESS",1);
                var tooOld=metricFixture("T001",REPORT,"WINDOW_TEST","FAILED",1);
                var future=metricFixture("T001",REPORT,"WINDOW_TEST","UNKNOWN",1);
                jdbc.update("UPDATE dispatch_plan SET created_at=? WHERE id=?",observed.minusDays(7),included.plan());
                jdbc.update("UPDATE dispatch_plan SET created_at=? WHERE id=?",observed.minusDays(7).minusNanos(1_000_000),tooOld.plan());
                jdbc.update("UPDATE dispatch_plan SET created_at=? WHERE id=?",observed.plusSeconds(1),future.plan());
                var named=org.mockito.Mockito.spy(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc));
                var queries=new java.util.concurrent.atomic.AtomicInteger();
                // 执行真实明细SQL后推进同一数据库连接的时钟，旧的每句NOW()算法会把临界条目从概览漏掉。
                org.mockito.Mockito.doAnswer(call -> {
                    Object result=call.callRealMethod();
                    if(queries.incrementAndGet()==1)jdbc.execute("SET timestamp="+(fixed+2));
                    return result;
                }).when(named).queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
                var measured=new BusinessMetrics(jdbc);
                org.springframework.test.util.ReflectionTestUtils.setField(measured,"named",named);
                var actor=new CurrentUser("T001","window-admin","",Set.of("WINDOW_TEST"),Set.of("report:sales"),true);
                var result=measured.read(actor,7);
                assertEquals(2,queries.get());
                assertEquals(1,metricItems(result.dispatch(),"SUCCESS"));assertEquals(1,metricItems(result.overview().dispatch(),"SUCCESS"));
                assertEquals(0,metricItems(result.dispatch(),"FAILED"));assertEquals(0,metricItems(result.overview().dispatch(),"FAILED"));
                assertEquals(0,metricItems(result.dispatch(),"UNKNOWN"));assertEquals(0,metricItems(result.overview().dispatch(),"UNKNOWN"));
            } finally {
                // 连接回到池前恢复真实数据库时钟，不能影响其他回归或后台任务。
                jdbc.execute("SET timestamp=0");
            }
        });
    }

    @Test @Order(25) void remainingExclusionCopiesKeepCleanupResumableAcrossManyTerminalPlans() {
        var fixture=retentionFixture("T001","EXECUTED","SUCCESS",1);
        var planRows=new ArrayList<Object[]>();var itemRows=new ArrayList<Object[]>();
        for(int i=0;i<500;i++) {
            String plan=UUID.randomUUID().toString().replace("-","");
            planRows.add(new Object[]{plan,fixture.preview(),"retention-extra:"+plan});
            itemRows.add(new Object[]{plan,REPORT,"retained-"+i});
        }
        // 模拟逐条展示事实已清理、排除单据号尚有副本的可恢复中间状态，仍保存每条清单的防重身份。
        jdbc.batchUpdate("INSERT INTO dispatch_plan(id,preview_id,tenant_id,user_id,status,item_count,idempotency_key,exclude_json,created_at,expires_at,updated_at) "
                +"VALUES (?,?,'T001','user1','CANCELLED',1,?,'[\"EXCLUDED-CANCELLED\"]',TIMESTAMPADD(DAY,-400,NOW()),TIMESTAMPADD(DAY,-400,NOW()),TIMESTAMPADD(DAY,-400,NOW()))",planRows);
        jdbc.batchUpdate("INSERT INTO dispatch_plan_item(plan_id,seq,report_id,report_name,catalog_version,record_id,company_code,fields_json,status,updated_at) "
                +"VALUES (?,1,?,'销售报表',1,?,'A','[]','PENDING',NOW(3))",itemRows);
        retention.sweep();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE preview_id=? AND JSON_LENGTH(exclude_json)>0",Integer.class,fixture.preview()));
        assertNotEquals("DATA_PURGED",purgeState(fixture));
        retention.sweep();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE preview_id=? AND JSON_LENGTH(exclude_json)>0",Integer.class,fixture.preview()));
        assertEquals("DATA_PURGED",purgeState(fixture));
        assertEquals(501,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id WHERE p.preview_id=?",Integer.class,fixture.preview()));
    }

    @Test @Order(80) void dictionaryPublicationValidatesScopeAndInvalidatesOldExecutionVersions() {
        var scope=new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,"A",REPORT);
        var before=dictionary(scope);var version=rawCatalog.find(REPORT).orElseThrow().catalogVersion();
        var preview=previews.preview(user(),null,new PreviewCommand(null,"api","销售报表",null,null,null)).snapshot();
        var change=new SemanticDictionaryService.Change(before.version(),dictionaryDefinition("甲公司"),"经业务核实口径");
        dictionaries.validate(admin(),scope,change);assertEquals(before.version(),dictionary(scope).version());
        var snapshot=dictionaries.snapshot(admin(),catalog.visibleReports(admin()));
        var saved=dictionaries.publish(admin(),scope,change);assertEquals(before.version()+1,saved.version());
        assertTrue(rawCatalog.find(REPORT).orElseThrow().catalogVersion()>version);
        assertEquals(List.of(saved.version(),before.version()),dictionaries.history(user(),scope).stream().map(SemanticDictionaryService.Entry::version).toList());
        assertEquals(409,assertThrows(ApiException.class,()->dictionaries.publish(admin(),scope,change)).getCode());
        assertThrows(ApiException.class,()->dictionaries.requireUnchanged(admin(),snapshot));
        assertThrows(ApiException.class,()->plans.create(user(),null,preview.preview().getId(),List.of(),"p0-old-dictionary"));
        String key=jdbc.queryForObject("SELECT policy_key FROM operations_policy WHERE tenant_id='T001' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.scope.companyCode'))='A' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.scope.reportId'))=?",String.class,REPORT);
        assertThrows(ApiException.class,()->policies.get("T001",key));assertThrows(ApiException.class,()->policies.history(admin(),key));
        for(var denied:List.of(new CurrentUser("T002","x","",Set.of("A"),Set.of("*"),true),new CurrentUser("T001","x","",Set.of("B"),Set.of("*"),true),new CurrentUser("T001","x","",Set.of("A"),Set.of("report:expense"),true))) {
            assertThrows(ApiException.class,()->dictionaries.read(denied,scope));assertThrows(ApiException.class,()->dictionaries.history(denied,scope));
        }
        assertThrows(ApiException.class,()->dictionaries.publish(user(),scope,new SemanticDictionaryService.Change(saved.version(),saved.definition(),"普通用户不能发布")));
    }
    @Test @Order(81) void concurrentDictionaryWritersPublishOneCompleteVersion() throws Exception {
        var scope=new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,"A",REPORT);long version=dictionary(scope).version();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++){final int writer=i;jobs.add(pool.submit(()->{start.await();try{dictionaries.publish(admin(),scope,new SemanticDictionaryService.Change(version,dictionaryDefinition("甲公司"+writer),"并发发布验证"));return true;}catch(ApiException failure){assertEquals(409,failure.getCode());return false;}}));}
            start.countDown();int success=0;for(var job:jobs)if(job.get(20,java.util.concurrent.TimeUnit.SECONDS))success++;
            assertEquals(1,success);assertEquals(version+1,dictionary(scope).version());
            assertEquals(version+2,dictionaries.history(admin(),scope).size());
        }finally{pool.shutdownNow();}
    }
    @Test @Order(82) void dictionaryHttpRejectsCoercionAndCrossCompanyScope() throws Exception {
        var headers=new HttpHeaders();headers.set("X-User-Id","admin");
        String endpoint="/api/semantic-quality/dictionary?domain=REPORT&companyCode=A&reportId="+REPORT;
        var valid=rest.exchange(endpoint,HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertEquals(0,JsonUtil.MAPPER.readTree(valid.getBody()).path("code").asInt());
        var form=JsonUtil.toMap(JsonUtil.toJson(new SemanticDictionaryService.Change(dictionary(new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,"A",REPORT)).version(),dictionaryDefinition("甲公司"),"结构化表单验证")));
        form.put("expectedVersion","2");
        var invalid=rest.exchange("/api/semantic-quality/dictionary/validate?domain=REPORT&companyCode=A&reportId="+REPORT,HttpMethod.POST,new HttpEntity<>(form,headers),String.class);
        assertEquals(422,JsonUtil.MAPPER.readTree(invalid.getBody()).path("code").asInt());
        headers.set("X-User-Id","user1");
        var denied=rest.exchange(endpoint.replace("companyCode=A","companyCode=NOT_AUTHORIZED"),HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertNotEquals(0,JsonUtil.MAPPER.readTree(denied.getBody()).path("code").asInt());
    }
    @Test @Order(83) void diagnosticOwnershipRevocationDeletionAndPaginationUseReliableEvents() {
        String c=qualityConversation(user().permissionVersion());var ids=new ArrayList<Long>();
        for(int i=0;i<25;i++)ids.add(diagnostic(c));
        String hidden=qualityConversation("revoked-version");for(int i=0;i<105;i++)diagnostic(hidden);
        var first=quality.list(user(),null);assertEquals(20,first.records().size());assertNotNull(first.nextCursor());
        assertEquals(ids.get(ids.size()-1).toString(),first.records().get(0).get("eventId"));
        var next=quality.list(user(),Long.valueOf(first.nextCursor()));assertEquals(5,next.records().size());assertNull(next.nextCursor());
        assertThrows(ApiException.class,()->quality.get(admin(),ids.get(0)));
        assertThrows(ApiException.class,()->quality.get(new CurrentUser("T002",user().userId(),"",Set.of("A"),Set.of("*"),false),ids.get(0)));
        var revoked=new CurrentUser(user().tenantId(),user().userId(),"",Set.of("B"),user().permissions(),false);
        assertThrows(ApiException.class,()->quality.get(revoked,ids.get(0)));
        retention.request(user(),c,"删除本次诊断会话");assertThrows(ApiException.class,()->quality.get(user(),ids.get(0)));assertTrue(quality.list(user(),null).records().isEmpty());
        assertThrows(ApiException.class,()->quality.submitFeedback(user(),ids.get(0),feedbackInput("删除后不可写")));
        retention.erase(c);
    }
    @Test @Order(84) void feedbackIsIdempotentScopedAndPagedBeforeOtherRounds() {
        String c=qualityConversation(user().permissionVersion());long original=diagnostic(c),another=diagnostic(c);
        var input=feedbackInput("电话13812345678，真正意图是保留下界");
        var one=quality.submitFeedback(user(),original,input);var replay=quality.submitFeedback(user(),original,input);
        assertEquals(one.get("feedbackEventId"),replay.get("feedbackEventId"));assertEquals(true,replay.get("replayed"));
        var changed=new HashMap<String,Object>(input);changed.put("note","电话13912345678，真正意图是保留下界");
        assertEquals(409,assertThrows(ApiException.class,()->quality.submitFeedback(user(),original,changed)).getCode());
        assertEquals(409,assertThrows(ApiException.class,()->quality.submitFeedback(user(),another,input)).getCode());
        for(int i=0;i<23;i++)quality.submitFeedback(user(),original,feedbackInput("本轮反馈 "+i));
        for(int i=0;i<205;i++)quality.submitFeedback(user(),another,feedbackInput("其他轮次反馈 "+i));
        var first=quality.feedback(user(),original,null);assertEquals(20,first.records().size());assertNotNull(first.nextCursor());
        var next=quality.feedback(user(),original,Long.valueOf(first.nextCursor()));assertEquals(4,next.records().size());assertNull(next.nextCursor());
        assertFalse(JsonUtil.toJson(next).contains("13812345678"));
        assertEquals(24,jdbc.queryForObject("SELECT COUNT(*) FROM trace_event WHERE conversation_id=? AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.message.toolName'))='assistant_feedback' AND JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.message.content')),'$.diagnosticId'))=?",Integer.class,c,String.valueOf(original)));
    }
    @Test @Order(85) void parallelFeedbackRetriesReturnTheSameDurableEvent() throws Exception {
        long id=diagnostic(qualityConversation(user().permissionVersion()));var input=feedbackInput("并发网络重试");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return quality.submitFeedback(user(),id,input);});var b=pool.submit(()->{start.await();return quality.submitFeedback(user(),id,input);});
            start.countDown();assertEquals(a.get(20,java.util.concurrent.TimeUnit.SECONDS).get("feedbackEventId"),b.get(20,java.util.concurrent.TimeUnit.SECONDS).get("feedbackEventId"));
        }finally{pool.shutdownNow();}
    }
    private SemanticDictionaryService.Entry dictionary(SemanticDictionary.Scope scope){return (SemanticDictionaryService.Entry)dictionaries.read(admin(),scope).get("entry");}
    private static SemanticDictionary.Definition dictionaryDefinition(String name){return new SemanticDictionary.Definition(name,List.of("甲方"),"Asia/Shanghai",4,List.of(),List.of(new SemanticDictionary.Field("amount",List.of("价款"),"来源销售金额","元","CNY",List.of(new SemanticDictionary.Unit("万元","10000")),List.of())));}
    private String qualityConversation(String permissionVersion){
        String c=conversations.create(user(),"real").getId();
        var state=new com.example.report.semantic.DialogueState();state.setBusinessPermissionVersion(permissionVersion);state.setBusinessCompanyCodes(List.of("A"));state.setBusinessReportIds(List.of(REPORT));
        jdbc.update("INSERT INTO semantic_dialogue(conversation_id,tenant_id,user_id,state_json,updated_at) VALUES (?,?,?,?,NOW(3))",c,user().tenantId(),user().userId(),JsonUtil.toJson(state));return c;
    }
    private long diagnostic(String c){String request=JsonUtil.newId();return conversations.logSemanticEvidence(c,user().userId(),"assistant_diagnostic",request,new PlanningDiagnostics().snapshot(request,"FAILED",null));}
    private static Map<String,Object> feedbackInput(String note){return Map.of("feedbackId",JsonUtil.newId(),"category","MISUNDERSTOOD","note",note);}

    /**
     * 留存测试专用快照的关联标识；不创建来源业务记录或执行网关。
     * @param preview 隔离测试库中的预览标识
     * @param plan 隔离测试库中的清单标识
     */
    record RetentionFixture(String preview,String plan) { }

    /** 生成400天前的合成清单，覆盖到期与保留路径；原始事实、结构化副本、规则及防重证据同时存在。 */
    private RetentionFixture retentionFixture(String tenant,String planState,String itemState,int count) {
        String preview=UUID.randomUUID().toString().replace("-","");
        String plan=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO dispatch_preview(id,tenant_id,user_id,source,report_ids,company_codes,query_json,summary_json,catalog_version,rule_version,permission_version,status,total_count,total_amount,expires_at,created_at,updated_at) "
                +"VALUES (?,?,'user1','api',?,'[\"A\"]','{\"reportQuery\":\"销售报表\"}','{}','catalog-test','rule-test','permission-test','ACTIVE',?,?,TIMESTAMPADD(DAY,-400,NOW()),TIMESTAMPADD(DAY,-400,NOW()),TIMESTAMPADD(DAY,-400,NOW()))",
                preview,tenant,JsonUtil.toJson(List.of(REPORT)),count,new java.math.BigDecimal("1234.56").multiply(java.math.BigDecimal.valueOf(count)));
        boolean confirmed=Set.of("EXECUTED","EXECUTING","REVIEW_REQUIRED").contains(planState);
        jdbc.update("INSERT INTO dispatch_plan(id,preview_id,tenant_id,user_id,status,item_count,idempotency_key,exclude_json,created_at,expires_at,confirmed_at,confirmed_by,updated_at,execution_version) "
                +"VALUES (?,?,?,'user1',?,?,?,'[\"RETENTION-EXCLUDED\"]',TIMESTAMPADD(DAY,-400,NOW()),TIMESTAMPADD(DAY,-400,NOW()),?,?,TIMESTAMPADD(DAY,-400,NOW()),?)",
                plan,preview,tenant,planState,count,"retention:"+plan,confirmed?java.time.LocalDateTime.now().minusDays(400):null,confirmed?"user1":null,confirmed?7:0);
        String fields=JsonUtil.toJson(List.of(Map.of("name","amount","type","decimal","value","1234.56"),Map.of("name","customerName","type","string","value","留存测试客户")));
        String customer=JsonUtil.toJson(Map.of("id","retention-customer","name","留存测试客户","aliases",List.of("客户别名")));
        String rule=JsonUtil.toJson(Map.of("ruleId",1,"version",1,"expression","amount > 1"));
        int attempts=Set.of("SUCCESS","FAILED","UNKNOWN").contains(itemState)?2:0;
        var rows=new ArrayList<Object[]>();
        for(int i=1;i<=count;i++) rows.add(new Object[]{fields,customer,plan,i,REPORT,"retention-"+i,rule,itemState,attempts,attempts>0?plan+"-"+i:null,"SKIPPED".equals(itemState)?"NOT_SENT":null});
        jdbc.batchUpdate("INSERT INTO dispatch_plan_item(fields_json,counterparty_json,plan_id,seq,report_id,report_name,catalog_version,record_id,doc_no,company_code,label,amount,biz_date,rule_id,rule_name,rule_version,rule_snapshot,status,attempt_count,external_request_id,error_code,error_message,updated_at) "
                +"VALUES (?,?,?,?,?,'销售报表',1,?,'RETENTION-SAMPLE','A','留存测试摘要',1234.56,'2026-01-01',1,'销售规则',1,?,?,?,?,?,'留存测试说明',NOW(3))",rows);
        return new RetentionFixture(preview,plan);
    }

    /** 读取清理不得改变的真实数据库证据，逐条比较而不是仅验证汇总数量。 */
    private List<Map<String,Object>> retainedEvidence(RetentionFixture fixture) {
        return jdbc.queryForList("SELECT i.id,i.plan_id,i.report_id,i.catalog_version,i.record_id,i.company_code,i.rule_id,i.rule_version,i.rule_snapshot,i.status,i.attempt_count,i.external_request_id,i.error_code,i.updated_at,"+
                "p.execution_version,p.idempotency_key,p.confirmed_at,p.confirmed_by FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id WHERE p.id=? ORDER BY i.id",fixture.plan());
    }

    private String purgeState(RetentionFixture fixture) {
        return jdbc.queryForObject("SELECT status_reason FROM dispatch_preview WHERE id=?",String.class,fixture.preview());
    }

    /** 指标回归使用当前时间的合成清单；明确调整租户、公司和报表范围，不调用真实派单或模型。 */
    private RetentionFixture metricFixture(String tenant,String report,String company,String state,int count) {
        var fixture=retentionFixture(tenant,"EXECUTED",state,count);
        jdbc.update("UPDATE dispatch_plan SET created_at=NOW(3) WHERE id=?",fixture.plan());
        jdbc.update("UPDATE dispatch_preview SET report_ids=?,company_codes=? WHERE id=?",JsonUtil.toJson(List.of(report)),JsonUtil.toJson(List.of(company)),fixture.preview());
        jdbc.update("UPDATE dispatch_plan_item SET report_id=?,company_code=? WHERE plan_id=?",report,company,fixture.plan());
        return fixture;
    }

    private static long metricItems(BusinessMetrics.Series series,String state) {
        return series.rows().stream().filter(row->state.equals(row.get("status"))).mapToLong(row->((Number)row.get("items")).longValue()).sum();
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
