package com.example.report.trace;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.*;
import com.example.report.dispatch.store.*;
import com.example.report.entity.*;
import com.example.report.mapper.*;
import com.example.report.support.DispatchHarness;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionSystemException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 独立 UUID 测试库；不启动应用、不执行演示重置、不依赖 Redis/模型/真实派单服务。 */
@EnabledIfEnvironmentVariable(named = "TRACE_IT", matches = "true")
class TracePersistenceIntegrationTest {
    private static String schema;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TransactionTemplate tx;
    private static SqlSessionTemplate sql;
    private DispatchHarness h;
    private TraceJournal journal;
    private TraceProjector projector;
    private DispatchAuditMapper auditMapper;
    private AgentMessageMapper messageMapper;
    private ConversationService conversations;
    private PlanRepository plans;
    private PreviewRepository previews;
    private PreviewService previewService;
    private PlanService planService;
    private DispatchGateway gateway;
    private DispatchService dispatch;
    private TraceReader reader;

    @BeforeAll static void database() throws Exception {
        schema = "trace_it_" + UUID.randomUUID().toString().replace("-", "");
        String host = System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("TRACE_DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?createDatabaseIfNotExist=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
        dataSource = new DriverManagerDataSource(url, System.getenv().getOrDefault("TRACE_DB_USER", "root"),
                System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(manager);
        // 模拟升级前会话，然后执行 V15，验证历史记录没有被重写或清理。
        Flyway.configure().dataSource(dataSource).target("14").load().migrate();
        jdbc.update("INSERT INTO agent_conversation(id,tenant_id,user_id,status,created_at,updated_at) VALUES ('legacy','T001','user1','active',NOW(),NOW())");
        jdbc.update("INSERT INTO agent_message(conversation_id,tenant_id,user_id,role,content,created_at) VALUES ('legacy','T001','user1','user','升级前的原话',NOW())");
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertEquals("升级前的原话", jdbc.queryForObject("SELECT content FROM agent_message WHERE conversation_id='legacy'", String.class));
        assertNull(jdbc.queryForObject("SELECT evidence_id FROM agent_message WHERE conversation_id='legacy'", Long.class));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(DispatchAuditMapper.class, AgentMessageMapper.class, AgentConversationMapper.class,
                DispatchPlanMapper.class, DispatchPlanItemMapper.class, DispatchPreviewMapper.class, DispatchPreviewItemMapper.class)) {
            configuration.addMapper(mapper);
        }
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @AfterAll static void dropOnlyOurDatabase() {
        if (jdbc != null && schema != null && schema.matches("trace_it_[a-f0-9]{32}")) {
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }

    @BeforeEach void setup() {
        for (String table : List.of("trace_event", "dispatch_audit", "agent_message", "dispatch_plan_item", "dispatch_plan",
                "dispatch_preview_item", "dispatch_preview", "agent_conversation", "dispatch_rule")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO dispatch_rule(id,tenant_id,report_id,company_code,name,expression,version,status,created_at) "
                + "VALUES (1,'T001',?,'*','原始规则','amount > 20',1,'published',NOW())", SALES);
        h = new DispatchHarness().put(SALES, candidate(SALES, "1", "SO1", "A", "服务器"));
        journal = spy(new TraceJournal(jdbc, tx));
        auditMapper = spy(sql.getMapper(DispatchAuditMapper.class));
        messageMapper = spy(sql.getMapper(AgentMessageMapper.class));
        projector = new TraceProjector(jdbc, auditMapper, messageMapper, h.props, manager);
        conversations = new ConversationService(sql.getMapper(AgentConversationMapper.class), messageMapper, h.props, journal, projector);
        previews = transactional(new MybatisPreviewRepository(sql.getMapper(DispatchPreviewMapper.class),
                sql.getMapper(DispatchPreviewItemMapper.class), sql.getMapper(AgentConversationMapper.class), journal, h.catalogService));
        plans = transactional(new MybatisPlanRepository(sql.getMapper(DispatchPlanMapper.class),
                sql.getMapper(DispatchPlanItemMapper.class), journal, new RuleEvidence(jdbc)));
        previewService = new PreviewService(h.catalogService, h.candidates, h.versions, previews, plans, h.props, tx);
        planService = new PlanService(previewService, previews, plans, h.versions, h.props, tx, h.quotas);
        gateway = mock(DispatchGateway.class);
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.ok());
        dispatch = new DispatchService(planService, previewService, plans, h.catalogService, h.candidates, h.versions,
                gateway, new AuditService(journal, projector), conversations, mock(ChatMemory.class), tx);
        reader = new TraceReader(jdbc);
    }

    @AfterEach void stopHeartbeat() { if (dispatch != null) dispatch.shutdownHeartbeats(); }

    @SuppressWarnings("unchecked") private static <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private DispatchPlan plan() {
        var conversation = conversations.create(USER1, "mock");
        conversations.logUser(conversation.getId(), USER1.userId(), "查询销售并派单");
        var preview = previewService.preview(USER1, conversation.getId(),
                new PreviewCommand(null, "api", null, List.of(SALES), null, null, null)).snapshot();
        return planService.create(USER1, conversation.getId(), preview.preview().getId(), List.of(), null).plan();
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    private void drain() {
        for (int i = 0; i < 20; i++) {
            jdbc.update("UPDATE trace_event SET next_attempt_at=NOW(3) WHERE delivery_status='PENDING'");
            projector.recover();
        }
    }

    @Test void projectionFailureSurvivesRestartAndDoesNotRepeatDispatchOrAudit() {
        DispatchPlan plan = plan();
        doThrow(new IllegalStateException("audit table temporarily unavailable")).when(auditMapper).insert(any(DispatchAudit.class));
        assertEquals(1, dispatch.confirm(USER1, plan.getId()).successCount());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM trace_event WHERE event_type='AUDIT'", Long.class));
        assertEquals(0, count("dispatch_audit"));
        assertEquals("SYNCING", reader.integrity(plans.find(plan.getId()).orElseThrow()).get("status"));
        projector = new TraceProjector(jdbc, sql.getMapper(DispatchAuditMapper.class), messageMapper, h.props, manager);
        drain();
        drain();
        assertEquals(2, count("dispatch_audit"));
        assertEquals("COMPLETE", reader.integrity(plans.find(plan.getId()).orElseThrow()).get("status"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE card_type='result'", Integer.class));
        dispatch.confirm(USER1, plan.getId());
        verify(gateway, times(1)).dispatch(any());
    }

    @Test void evidenceFailureBeforeSendRollsBackItemAndPreventsExternalRequest() {
        DispatchPlan plan = plan();
        doThrow(new IllegalStateException("journal unavailable")).when(journal).audit(any());
        assertEquals(503, assertThrows(ApiException.class, () -> dispatch.confirm(USER1, plan.getId())).getCode());
        DispatchPlanItem item = plans.items(plan.getId()).get(0);
        assertEquals(DispatchPlanItem.PENDING, item.getStatus());
        assertEquals(0, item.getAttemptCount());
        assertEquals(DispatchPlan.PENDING, plans.find(plan.getId()).orElseThrow().getStatus());
        verifyNoInteractions(gateway);
    }

    @Test void resultEvidenceFailureRetainsIntentAndReconciliationCompletesWithoutResend() {
        DispatchPlan plan = plan();
        doAnswer(inv -> {
            DispatchAudit audit = inv.getArgument(0);
            if ("RESULT".equals(audit.getPhase())) throw new IllegalStateException("lost result commit");
            return inv.callRealMethod();
        }).when(journal).audit(any());
        assertThrows(ApiException.class, () -> dispatch.confirm(USER1, plan.getId()));
        assertEquals(DispatchPlanItem.UNKNOWN, plans.items(plan.getId()).get(0).getStatus());
        assertEquals(DispatchPlan.REVIEW_REQUIRED, plans.find(plan.getId()).orElseThrow().getStatus());
        when(gateway.lookup(anyString(), anyString())).thenReturn(new DispatchGateway.Lookup(DispatchGateway.LookupStatus.SUCCESS, null, "已成功"));
        assertEquals(1, dispatch.reconcile(USER1, plan.getId()).successCount());
        drain();
        assertEquals(2, count("dispatch_audit"));
        assertEquals("COMPLETE", reader.integrity(plans.find(plan.getId()).orElseThrow()).get("status"));
        verify(gateway, times(1)).dispatch(any());
    }

    @Test void failedResultCardCommitRollsBackPlanFinishAndCanBeRecovered() {
        DispatchPlan plan = plan();
        doAnswer(inv -> {
            AgentMessage message = inv.getArgument(0);
            if ("result".equals(message.getCardType())) throw new IllegalStateException("card commit failed");
            return inv.callRealMethod();
        }).when(journal).message(any(), anyBoolean(), anyString());
        assertThrows(ApiException.class, () -> dispatch.confirm(USER1, plan.getId()));
        assertEquals(DispatchPlan.REVIEW_REQUIRED, plans.find(plan.getId()).orElseThrow().getStatus());
        assertEquals(DispatchPlanItem.SUCCESS, plans.items(plan.getId()).get(0).getStatus());
        doCallRealMethod().when(journal).message(any(), anyBoolean(), anyString());
        assertEquals(1, dispatch.reconcile(USER1, plan.getId()).successCount());
        drain();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE card_type='result'", Integer.class));
        verify(gateway, times(1)).dispatch(any());
    }

    @Test void messageInsertionAndCountersRollbackTogetherAndRecoverInOrder() {
        var conversation = conversations.create(USER1, "mock");
        AgentMessageMapper real = sql.getMapper(AgentMessageMapper.class);
        doAnswer(inv -> { real.insert(inv.<AgentMessage>getArgument(0)); throw new IllegalStateException("crash after insert"); })
                .when(messageMapper).insert(any(AgentMessage.class));
        conversations.logUser(conversation.getId(), USER1.userId(), "第一条");
        conversations.logUser(conversation.getId(), USER1.userId(), "第二条");
        assertEquals(0, count("agent_message"));
        assertEquals(0, jdbc.queryForObject("SELECT message_count FROM agent_conversation WHERE id=?", Integer.class, conversation.getId()));
        projector = new TraceProjector(jdbc, auditMapper, real, h.props, manager);
        drain();
        assertEquals(List.of("第一条", "第二条"), jdbc.queryForList("SELECT content FROM agent_message ORDER BY id", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT message_count FROM agent_conversation WHERE id=?", Integer.class, conversation.getId()));
        drain();
        assertEquals(2, count("agent_message"));
    }

    @Test void previewAndPlanCardsSurviveWithoutCallingThePostCommitCardLogger() {
        DispatchPlan plan = plan();
        drain();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE card_type='preview'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE card_type='plan'", Integer.class));
        String snapshot = plans.items(plan.getId()).get(0).getRuleSnapshot();
        jdbc.update("UPDATE dispatch_rule SET expression='amount > 99999',name='已变更规则' WHERE id=1");
        assertEquals("amount > 20", JsonUtil.toMap(snapshot).get("expression"));
        assertEquals(snapshot, plans.items(plan.getId()).get(0).getRuleSnapshot());
    }

    @Test void concurrentProjectorsProduceOneMessageAndOneCounterIncrement() throws Exception {
        var conversation = conversations.create(USER1, "mock");
        AgentMessage message = new AgentMessage();
        message.setTenantId(USER1.tenantId()); message.setUserId(USER1.userId()); message.setConversationId(conversation.getId());
        message.setRole("user"); message.setContent("并发补写"); message.setCreatedAt(java.time.LocalDateTime.now());
        long id = journal.message(message, true, "one-message");
        TraceProjector second = new TraceProjector(jdbc, auditMapper, messageMapper, h.props, manager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> { await(start); projector.deliver(id); });
            Future<?> b = pool.submit(() -> { await(start); second.deliver(id); });
            start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        drain();
        assertEquals(1, count("agent_message"));
        assertEquals(1, jdbc.queryForObject("SELECT message_count FROM agent_conversation WHERE id=?", Integer.class, conversation.getId()));
    }

    @Test void tracePaginationReturnsAllMessagesWithoutTruncatingTextAndRetryNeverDispatches() {
        DispatchPlan plan = plan();
        String content = "完整原话".repeat(600);
        for (int i = 0; i < 105; i++) conversations.logUser(plan.getConversationId(), USER1.userId(), content + i);
        drain();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM agent_message", Long.class);
        assertEquals(108, total);
        long cursor = 0, read = 0;
        do {
            var page = reader.page(plan, "messages", cursor, 50);
            assertEquals(total, page.total());
            read += page.records().size();
            for (var row : page.records()) if (String.valueOf(row.get("content")).startsWith("完整原话")) {
                assertTrue(String.valueOf(row.get("content")).length() > 2000);
            }
            if (page.nextCursor() == null) break;
            cursor = page.nextCursor();
        } while (true);
        assertEquals(total, read);
        reader.retry(plan);
        verifyNoInteractions(gateway);
    }

    private static void await(CountDownLatch start) {
        try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }

    @Test void committedLargePreviewRetainsDetailsWhenCommitAcknowledgementIsLost() {
        h.put(SALES, candidate(SALES,"1","SO1","A","one"), candidate(SALES,"2","SO2","A","two"));
        h.props.getPreview().setMaxItems(1);
        var conversation = conversations.create(USER1, "mock");
        TransactionOperations lostAck = new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                tx.execute(action);
                throw new TransactionSystemException("lost commit acknowledgement");
            }
        };
        var service = new PreviewService(h.catalogService, h.candidates, h.versions, previews, plans, h.props, lostAck);
        assertThrows(TransactionSystemException.class, () -> service.preview(USER1, conversation.getId(),
                new PreviewCommand(null,"api",null,List.of(SALES),null,null,null)));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM dispatch_preview", String.class));
        assertEquals(2, count("dispatch_preview_item"));
        assertEquals(2, jdbc.queryForObject("SELECT total_count FROM dispatch_preview", Integer.class));
        drain(); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE card_type='preview'", Integer.class));
    }

    @Test void rolledBackActivationStillCleansUnpublishedLargePreview() {
        h.put(SALES, candidate(SALES,"1","SO1","A","one"), candidate(SALES,"2","SO2","A","two"));
        h.props.getPreview().setMaxItems(1);
        var conversation = conversations.create(USER1, "mock");
        TransactionOperations rollback = new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return tx.execute(status -> { action.doInTransaction(status); throw new IllegalStateException("rollback activation"); });
            }
        };
        var service = new PreviewService(h.catalogService, h.candidates, h.versions, previews, plans, h.props, rollback);
        assertThrows(IllegalStateException.class, () -> service.preview(USER1, conversation.getId(),
                new PreviewCommand(null,"api",null,List.of(SALES),null,null,null)));
        assertEquals(0, count("dispatch_preview")); assertEquals(0, count("dispatch_preview_item"));
    }

    private String buildingFixture() {
        var conversation = conversations.create(USER1, "mock");
        var outcome = previewService.preview(USER1, conversation.getId(), new PreviewCommand(null,"api",null,List.of(SALES),null,null,null));
        String id = outcome.snapshot().preview().getId();
        jdbc.update("UPDATE dispatch_preview SET status='BUILDING',updated_at=DATE_SUB(NOW(),INTERVAL 11 MINUTE) WHERE id=?", id);
        return id;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void cleanupAndActivationSerializeWithoutDeletingActiveDetails(boolean activationWins) throws Exception {
        String id = buildingFixture();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> tx.execute(status -> {
                sql.getMapper(DispatchPreviewMapper.class).lockState(id);
                locked.countDown(); await(release);
                if (activationWins) assertTrue(previews.transition(id,"BUILDING","ACTIVE",null,java.time.LocalDateTime.now()));
                else previews.deleteBuilding(id);
                return true;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var loser = pool.submit(() -> {
                started.countDown();
                if (activationWins) previews.deleteBuilding(id);
                else assertFalse(previews.transition(id,"BUILDING","ACTIVE",null,java.time.LocalDateTime.now()));
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> loser.get(150,TimeUnit.MILLISECONDS));
            release.countDown(); winner.get(5,TimeUnit.SECONDS); loser.get(5,TimeUnit.SECONDS);
            assertEquals(activationWins ? 1 : 0, count("dispatch_preview"));
            assertEquals(activationWins ? 1 : 0, count("dispatch_preview_item"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void orphanCleanupRechecksHeartbeatAndAppendCannotWriteAfterDeletion() {
        String id = buildingFixture();
        var item = previews.items(id).get(0); item.setId(null); item.setSeq(1); item.setRecordId("2"); item.setDocNo("SO2");
        previews.appendItems(List.of(item));
        previews.deleteBuildingBefore(id, java.time.LocalDateTime.now().minusMinutes(10));
        assertEquals(2, count("dispatch_preview_item"));
        previews.deleteBuilding(id);
        assertEquals(0, count("dispatch_preview_item"));
        assertThrows(IllegalStateException.class, () -> previews.appendItems(List.of(item)));
        assertEquals(0, count("dispatch_preview_item"));
    }

    @Test void staleBuildingSummaryCannotRevertAnActivePreview() {
        String id = buildingFixture();
        DispatchPreview stale = previews.find(id).orElseThrow();
        previews.transition(id,"BUILDING","ACTIVE",null,java.time.LocalDateTime.now());
        assertThrows(IllegalStateException.class, () -> previews.updateBuilding(stale));
        assertEquals("ACTIVE", previews.find(id).orElseThrow().getStatus());
        assertEquals(1, count("dispatch_preview_item"));
    }
}
