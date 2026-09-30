package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.conversation.ConversationService;
import com.example.report.entity.DispatchAudit;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.entity.DispatchPreview;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.Candidate;
import com.example.report.support.DispatchHarness;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.SALES;
import static com.example.report.support.TestCatalog.USER1;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 派单执行：归属、状态、版本、认领（只执行一次）、执行前复核、逐条结果与审计、重复确认幂等
 */
class DispatchServiceTest {

    private static final String CONVERSATION = "conversation-1";

    private final Candidate first = candidate(SALES, "1", "SO2026001", "A", "服务器");
    private final Candidate second = candidate(SALES, "2", "SO2026002", "A", "交换机");
    private final Candidate third = candidate(SALES, "3", "SO2026003", "A", "云服务");

    private DispatchHarness h;
    private DispatchGateway gateway;
    private AuditService audit;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        h = new DispatchHarness().put(SALES, first, second, third);
        gateway = mock(DispatchGateway.class);
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.ok());
        audit = mock(AuditService.class);
        service = new DispatchService(h.plans, h.previews, h.store.plans(), h.catalogService, h.candidates, h.versions,
                gateway, audit, mock(ConversationService.class), mock(ChatMemory.class),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    private PreviewSnapshot preview(CurrentUser user) {
        return h.previews.preview(user, CONVERSATION, new PreviewCommand(null, "api", "销售报表", null, null, null, null)).snapshot();
    }

    private String plan(CurrentUser user) {
        preview(user);
        return h.plans.create(user, CONVERSATION, null, List.of(), null).plan().getId();
    }

    private DispatchPlan planRow(String planId) {
        return h.store.plans().find(planId).orElseThrow();
    }

    @Test void administratorReconciliationUsesActualActorAndOriginalOperatorWithoutResending() {
        h.put(SALES,first);
        String planId=plan(USER1);
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.fail("RESULT_UNKNOWN","unknown"));
        assertThrows(ApiException.class,()->service.confirm(USER1,planId));
        var authenticated=mock(AuthenticatedDispatchGateway.class);
        var admin=new CurrentUser("T001","admin","Admin",Set.of("A"),Set.of("*"),true);
        String request=h.store.plans().items(planId).get(0).getExternalRequestId();
        when(authenticated.lookupForOperator(admin,USER1.userId(),request)).thenReturn(new DispatchGateway.Lookup(DispatchGateway.LookupStatus.SUCCESS,null,null));
        service=new DispatchService(h.plans,h.previews,h.store.plans(),h.catalogService,h.candidates,h.versions,
                authenticated,audit,mock(ConversationService.class),mock(ChatMemory.class),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
        assertThrows(ApiException.class,()->service.reconcileForOperator(USER1,USER1.userId(),planId));
        var foreign=new CurrentUser("T002","admin","",Set.of("A"),Set.of("*"),true);
        assertThrows(ApiException.class,()->service.reconcileForOperator(foreign,USER1.userId(),planId));
        var restricted=new CurrentUser("T001","limited","",Set.of("B"),Set.of("*"),true);
        assertThrows(ApiException.class,()->service.reconcileForOperator(restricted,USER1.userId(),planId));
        assertEquals(1,service.reconcileForOperator(admin,USER1.userId(),planId).successCount());
        verify(authenticated).lookupForOperator(admin,USER1.userId(),request);
        verify(authenticated,never()).dispatch(any());verify(authenticated,never()).dispatch(any(),any());
        verify(audit).record(eq(admin),any(),any(),anyLong(),eq(request),eq("SUCCESS"),isNull(),anyString());
    }

    @Test
    void recordsThatNoLongerQualifyAreSkippedWithoutCallingTheGateway() {
        String planId = plan(USER1);
        // 预览之后 SO2026002 被改成不满足规则（或已派单 / 失去权限）：当前复核结果里没有它
        h.put(SALES, first, third);

        DispatchResultPayload result = service.confirm(USER1, planId);

        assertEquals(2, result.successCount());
        assertEquals(1, result.failedCount());
        assertEquals("SO2026002", result.failed().get(0).docNo());
        assertEquals(DispatchPlanItem.SKIPPED, result.failed().get(0).outcome());
        verify(gateway, never()).dispatch(argThat(r -> r.record().docNo().equals("SO2026002")));
        // 没派出去的记录同样留审计，结果写明 SKIPPED
        verify(audit).record(eq(USER1), any(), argThat(c -> c.docNo().equals("SO2026002")), anyLong(), isNull(),
                eq(DispatchAudit.OUTCOME_SKIPPED), eq("RECORD_CHANGED"), anyString());
        assertEquals(DispatchPlan.EXECUTED, planRow(planId).getStatus());
    }

    @Test
    void durableEvidenceFailurePreventsSendingNewRequests() {
        String planId = plan(USER1);
        doThrow(new RuntimeException("db down")).when(audit)
                .record(any(), any(), argThat(c -> c.docNo().equals("SO2026001")), any(), any(), any(), any(), any());
        ApiException error = assertThrows(ApiException.class, () -> service.confirm(USER1, planId));
        assertEquals(503, error.getCode());
        verifyNoInteractions(gateway);
        assertEquals(DispatchPlan.PENDING, planRow(planId).getStatus());
    }

    @Test
    void auditRecordCarriesTheWholeChain() {
        String planId = plan(USER1);
        service.confirm(USER1, planId, "trace-0001");
        ArgumentCaptor<AuditService.Context> ctx = ArgumentCaptor.forClass(AuditService.Context.class);
        verify(audit, times(3)).record(eq(USER1), ctx.capture(), any(), anyLong(), anyString(), eq(DispatchAudit.OUTCOME_SUCCESS),
                isNull(), anyString());
        AuditService.Context c = ctx.getValue();
        DispatchPreview preview = h.store.previews().find(planRow(planId).getPreviewId()).orElseThrow();
        assertEquals(DispatchService.SOURCE_AGENT, c.source());
        assertEquals(CONVERSATION, c.conversationId());
        assertEquals(preview.getId(), c.previewId());
        assertEquals(planId, c.planId());
        assertEquals(preview.getRuleVersion(), c.ruleFingerprint());
        assertEquals(USER1.permissionVersion(), c.permissionVersion());
        assertEquals("trace-0001", c.traceId());
    }

    @Test
    void confirmingTwiceReturnsTheFirstResultWithoutDispatchingAgain() {
        // T-DISPATCH-02：重复确认只产生一次外部派单
        String planId = plan(USER1);
        DispatchResultPayload firstResult = service.confirm(USER1, planId);
        DispatchResultPayload again = service.confirm(USER1, planId);
        assertFalse(firstResult.replayed());
        assertTrue(again.replayed());
        assertEquals(firstResult.successCount(), again.successCount());
        verify(gateway, times(3)).dispatch(any());
    }

    @Test
    void externalRequestIdIsStablePerItem() {
        String planId = plan(USER1);
        service.confirm(USER1, planId);
        ArgumentCaptor<DispatchGateway.DispatchRequest> requests = ArgumentCaptor.forClass(DispatchGateway.DispatchRequest.class);
        verify(gateway, times(3)).dispatch(requests.capture());
        List<DispatchPlanItem> items = h.store.plans().items(planId);
        for (int i = 0; i < 3; i++) {
            assertEquals(planId + "-" + items.get(i).getId(), requests.getAllValues().get(i).externalRequestId());
            assertEquals(items.get(i).getExternalRequestId(), requests.getAllValues().get(i).externalRequestId());
        }
    }

    @Test
    void anotherUserOrTenantCannotConfirm() {
        // T-DISPATCH-06：不同用户不能执行他人的计划；同名用户换了租户也不行
        String planId = plan(USER1);
        CurrentUser otherTenant = new CurrentUser("T002", "user1", "外租户同名用户", Set.of("A"), TestCatalog.DEMO_PERMISSIONS, false);
        assertEquals(404, assertThrows(ApiException.class, () -> service.confirm(TestCatalog.USER2, planId)).getCode());
        assertEquals(404, assertThrows(ApiException.class, () -> service.confirm(otherTenant, planId)).getCode());
        verify(gateway, never()).dispatch(any());
        assertEquals(DispatchPlan.PENDING, planRow(planId).getStatus());
    }

    @Test
    void ruleChangeExpiresThePendingPlan() {
        // T-DISPATCH-07
        String planId = plan(USER1);
        h.ruleVersion.set("rules-v2");
        ApiException e = assertThrows(ApiException.class, () -> service.confirm(USER1, planId));
        assertTrue(e.getMessage().contains("规则已更新"), e.getMessage());
        assertEquals(DispatchPlan.EXPIRED, planRow(planId).getStatus());
        assertEquals(StateReason.RULE_CHANGED, planRow(planId).getStatusReason());
        verify(gateway, never()).dispatch(any());
    }

    @Test
    void permissionChangeExpiresThePendingPlan() {
        // T-PREVIEW-06：权限范围变化（这里多了一家公司）后旧预览、旧清单都不能执行
        String planId = plan(USER1);
        CurrentUser widened = new CurrentUser("T001", "user1", "用户1", Set.of("A", "B"), TestCatalog.DEMO_PERMISSIONS, false);
        ApiException e = assertThrows(ApiException.class, () -> service.confirm(widened, planId));
        assertEquals(400, e.getCode());
        assertEquals(StateReason.PERMISSION_CHANGED, planRow(planId).getStatusReason());
    }

    @Test
    void catalogChangeExpiresThePendingPlan() {
        // T-PREVIEW-05：报表定义变化（目录版本 +1）或报表被停用后，旧预览、旧清单不能执行
        String planId = plan(USER1);
        h.catalog.replace(TestCatalog.with(h.catalog.get(SALES), 2, "PUBLISHED", true));
        ApiException e = assertThrows(ApiException.class, () -> service.confirm(USER1, planId));
        assertTrue(e.getMessage().contains("报表目录已变更"), e.getMessage());
        assertEquals(StateReason.CATALOG_CHANGED, planRow(planId).getStatusReason());
    }

    @Test
    void expiredCancelledAndSupersededPlansCannotBeExecuted() {
        // T-DISPATCH-05
        String expired = plan(USER1);
        h.store.updatePlan(expired, p -> p.setExpiresAt(LocalDateTime.now().minusSeconds(1)));
        assertTrue(assertThrows(ApiException.class, () -> service.confirm(USER1, expired)).getMessage().contains("有效期"));
        assertEquals(StateReason.TTL, planRow(expired).getStatusReason());

        String cancelled = plan(USER1);
        service.cancel(USER1, cancelled);
        assertTrue(assertThrows(ApiException.class, () -> service.confirm(USER1, cancelled)).getMessage().contains("已取消"));

        String superseded = plan(USER1);
        preview(USER1);
        assertTrue(assertThrows(ApiException.class, () -> service.confirm(USER1, superseded)).getMessage().contains("新的预览"));
        verify(gateway, never()).dispatch(any());
    }

    @Test
    void gatewayTimeoutRequiresReconciliationInsteadOfBlindRetry() {
        String planId = plan(USER1);
        when(gateway.dispatch(argThat(r -> r != null && r.record().docNo().equals("SO2026002"))))
                .thenReturn(DispatchGateway.Outcome.fail("REMOTE_REJECTED", "派单接口拒绝"));
        when(gateway.dispatch(argThat(r -> r != null && r.record().docNo().equals("SO2026003"))))
                .thenThrow(new IllegalStateException("连接超时"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.confirm(USER1, planId)).getCode());
        List<DispatchPlanItem> items = h.store.plans().items(planId);
        assertEquals(List.of(DispatchPlanItem.SUCCESS, DispatchPlanItem.FAILED, DispatchPlanItem.UNKNOWN),
                items.stream().map(DispatchPlanItem::getStatus).toList());
        assertEquals("REMOTE_REJECTED", items.get(1).getErrorCode());
        assertEquals("RESULT_UNKNOWN", items.get(2).getErrorCode());
        assertEquals(1, items.get(2).getAttemptCount());
        assertEquals(DispatchPlan.REVIEW_REQUIRED, planRow(planId).getStatus());
        assertEquals(409, assertThrows(ApiException.class, () -> service.confirm(USER1, planId)).getCode());
    }

    @Test
    void reconciliationUsesGatewayRequestIdWithoutResending() {
        String planId = plan(USER1);
        when(gateway.dispatch(argThat(r -> r != null && r.record().docNo().equals("SO2026002"))))
                .thenThrow(new IllegalStateException("连接中断"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.confirm(USER1, planId)).getCode());
        String requestId = h.store.plans().items(planId).get(1).getExternalRequestId();
        when(gateway.lookup(USER1.tenantId(), requestId))
                .thenReturn(new DispatchGateway.Lookup(DispatchGateway.LookupStatus.SUCCESS, null, "已受理"));

        DispatchResultPayload result = service.reconcile(USER1, planId);
        assertEquals(3, result.successCount());
        verify(audit).record(eq(USER1), argThat(ctx -> "reconcile".equals(ctx.source())),
                argThat(c -> "SO2026002".equals(c.docNo())), anyLong(), eq(requestId),
                eq(DispatchPlanItem.SUCCESS), isNull(), contains("核对"));
        assertEquals(DispatchPlan.EXECUTED, planRow(planId).getStatus());
        verify(gateway, times(3)).dispatch(any());
        verify(gateway).lookup(USER1.tenantId(), requestId);
    }

    @Test
    void planConfirmationUsesDispatchQuota() {
        String planId = plan(USER1);
        com.example.report.config.ResourceQuotaService quotas =
                mock(com.example.report.config.ResourceQuotaService.class);
        var permit = mock(com.example.report.config.ResourceQuotaService.Permit.class);
        when(quotas.acquire(eq(USER1), eq("dispatch"), anyCollection())).thenReturn(permit);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "quotas", quotas);

        assertEquals(3, service.confirm(USER1, planId).successCount());
        verify(quotas).acquire(eq(USER1), eq("dispatch"), argThat(ids -> ids.contains(SALES)));
        verify(permit).close();
    }

    @Test
    void staleConcurrentReconciliationCannotOverwriteSuccessfulRetry() throws Exception {
        h.put(SALES, first);
        String planId = plan(USER1);
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.fail("RESULT_UNKNOWN", "unknown"));
        assertThrows(ApiException.class, () -> service.confirm(USER1, planId));
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(gateway.lookup(anyString(), anyString())).thenAnswer(call -> {
            if (Thread.currentThread().getName().equals("slow-reconcile")) {
                lookupStarted.countDown();
                if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
            }
            return new DispatchGateway.Lookup(DispatchGateway.LookupStatus.FAILED, "TEMPORARY", "retryable");
        });
        Thread stale = new Thread(() -> assertThrows(ApiException.class, () -> service.reconcile(USER1, planId)),
                "slow-reconcile");
        stale.start();
        assertTrue(lookupStarted.await(5, TimeUnit.SECONDS));
        service.reconcile(USER1, planId);
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.ok());
        service.retryFailed(USER1, planId);
        resume.countDown();
        stale.join(5000);
        assertFalse(stale.isAlive());
        assertEquals(DispatchPlanItem.SUCCESS, h.store.plans().items(planId).get(0).getStatus());
        assertEquals(1, h.store.plans().find(planId).orElseThrow().getSuccessCount());
    }

    @Test
    void staleReconciliationCannotResolveUnknownFromANewerAttempt() throws Exception {
        h.put(SALES, first);
        String planId = plan(USER1);
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.fail("RESULT_UNKNOWN", "unknown"));
        assertThrows(ApiException.class, () -> service.confirm(USER1, planId));
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch resumeLookup = new CountDownLatch(1);
        CountDownLatch retryStarted = new CountDownLatch(1);
        CountDownLatch resumeRetry = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean firstLookup = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(gateway.lookup(anyString(), anyString())).thenAnswer(call -> {
            if (firstLookup.getAndSet(false)) {
                lookupStarted.countDown();
                if (!resumeLookup.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lookup timeout");
            }
            return new DispatchGateway.Lookup(DispatchGateway.LookupStatus.FAILED, "TEMPORARY", "retryable");
        });
        PlanRepository unreliable = spy(h.store.plans());
        doAnswer(inv -> {
            DispatchPlanItem item = inv.getArgument(0);
            if (DispatchPlanItem.SUCCESS.equals(item.getStatus())) throw new IllegalStateException("write failed");
            return inv.callRealMethod();
        }).when(unreliable).updateItem(any(), anyLong());
        service = new DispatchService(h.plans, h.previews, unreliable, h.catalogService, h.candidates, h.versions,
                gateway, audit, mock(ConversationService.class), mock(ChatMemory.class),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApiException> stale = pool.submit(() -> assertThrows(ApiException.class,
                    () -> service.reconcile(USER1, planId)));
            assertTrue(lookupStarted.await(5, TimeUnit.SECONDS));
            service.reconcile(USER1, planId);
            when(gateway.dispatch(any())).thenAnswer(call -> {
                retryStarted.countDown();
                if (!resumeRetry.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("retry timeout");
                return DispatchGateway.Outcome.ok();
            });
            Future<ApiException> retry = pool.submit(() -> assertThrows(ApiException.class,
                    () -> service.retryFailed(USER1, planId)));
            assertTrue(retryStarted.await(5, TimeUnit.SECONDS));
            resumeLookup.countDown();
            assertEquals(409, stale.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(DispatchPlanItem.UNKNOWN, h.store.plans().items(planId).get(0).getStatus());
            resumeRetry.countDown();
            assertEquals(409, retry.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(DispatchPlan.REVIEW_REQUIRED, planRow(planId).getStatus());
            when(gateway.lookup(anyString(), anyString())).thenReturn(
                    new DispatchGateway.Lookup(DispatchGateway.LookupStatus.SUCCESS, null, null));
            assertEquals(1, service.reconcile(USER1, planId).successCount());
            assertEquals(DispatchPlan.EXECUTED, planRow(planId).getStatus());
            verify(gateway, times(2)).dispatch(any());
        } finally {
            resumeLookup.countDown();
            resumeRetry.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void retryOnlyExplicitFailuresAndReuseExternalRequestId() {
        String planId = plan(USER1);
        when(gateway.dispatch(argThat(r -> r != null && r.record().docNo().equals("SO2026002"))))
                .thenReturn(DispatchGateway.Outcome.fail("TEMPORARY", "稍后重试"))
                .thenAnswer(inv -> {
                    DispatchPlanItem persisted = h.store.plans().items(planId).get(1);
                    assertEquals(DispatchPlanItem.UNKNOWN, persisted.getStatus(), "重发前必须留下可核对状态");
                    assertEquals(2, persisted.getAttemptCount());
                    return DispatchGateway.Outcome.ok();
                });
        DispatchResultPayload firstResult = service.confirm(USER1, planId);
        assertEquals(1, firstResult.failedCount());
        String requestId = h.store.plans().items(planId).get(1).getExternalRequestId();

        DispatchResultPayload retried = service.retryFailed(USER1, planId);
        assertEquals(3, retried.successCount());
        assertEquals(0, retried.failedCount());
        assertEquals(DispatchPlan.EXECUTED, planRow(planId).getStatus());
        assertEquals(2, h.store.plans().items(planId).get(1).getAttemptCount());
        verify(gateway, times(2)).dispatch(argThat(r -> r.record().docNo().equals("SO2026002")
                && requestId.equals(r.externalRequestId())));
        verify(gateway, times(1)).dispatch(argThat(r -> r.record().docNo().equals("SO2026001")));
        verify(gateway, times(1)).dispatch(argThat(r -> r.record().docNo().equals("SO2026003")));
    }

    @Test
    void retryResultWriteFailureRemainsReconciliable() {
        String planId = plan(USER1);
        when(gateway.dispatch(argThat(r -> r != null && r.record().docNo().equals("SO2026002"))))
                .thenReturn(DispatchGateway.Outcome.fail("TEMPORARY", "稍后重试"), DispatchGateway.Outcome.ok());
        service.confirm(USER1, planId);
        String requestId = h.store.plans().items(planId).get(1).getExternalRequestId();

        PlanRepository unreliable = spy(h.store.plans());
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(inv -> {
            DispatchPlanItem item = inv.getArgument(0);
            if (item.getDocNo().equals("SO2026002") && DispatchPlanItem.SUCCESS.equals(item.getStatus())
                    && failOnce.getAndSet(false)) {
                throw new IllegalStateException("结果写库失败");
            }
            return inv.callRealMethod();
        }).when(unreliable).updateItem(any(), anyLong());
        service = new DispatchService(h.plans, h.previews, unreliable, h.catalogService, h.candidates, h.versions,
                gateway, audit, mock(ConversationService.class), mock(ChatMemory.class),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());

        assertThrows(ApiException.class, () -> service.retryFailed(USER1, planId));
        assertEquals(DispatchPlan.REVIEW_REQUIRED, planRow(planId).getStatus());
        assertEquals(DispatchPlanItem.UNKNOWN, h.store.plans().items(planId).get(1).getStatus());

        when(gateway.lookup(USER1.tenantId(), requestId))
                .thenReturn(new DispatchGateway.Lookup(DispatchGateway.LookupStatus.SUCCESS, null, null));
        DispatchResultPayload reconciled = service.reconcile(USER1, planId);
        assertEquals(3, reconciled.successCount());
        verify(gateway, times(2)).dispatch(argThat(r -> r.record().docNo().equals("SO2026002")));
    }

    @Test
    void executedPreviewCannotProduceAnotherPlan() {
        String planId = plan(USER1);
        String previewId = planRow(planId).getPreviewId();
        service.confirm(USER1, planId);
        assertEquals(DispatchPreview.CONSUMED, h.store.previews().find(previewId).orElseThrow().getStatus());
        ApiException e = assertThrows(ApiException.class, () -> h.plans.create(USER1, CONVERSATION, previewId, List.of(), null));
        assertTrue(e.getMessage().contains("已经执行过派单"), e.getMessage());
    }

    @Test
    void concurrentConfirmationsExecuteOnlyOnce() throws Exception {
        String planId = plan(USER1);
        CountDownLatch inGateway = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.dispatch(any())).thenAnswer(inv -> {
            inGateway.countDown();
            release.await(5, TimeUnit.SECONDS);
            return DispatchGateway.Outcome.ok();
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DispatchResultPayload> firstCall = pool.submit(() -> service.confirm(USER1, planId));
            assertTrue(inGateway.await(5, TimeUnit.SECONDS));
            // 第一个请求正在执行：第二个请求拿不到清单，得到"正在执行"
            ApiException e = assertThrows(ApiException.class, () -> service.confirm(USER1, planId));
            assertEquals(409, e.getCode());
            release.countDown();
            assertEquals(3, firstCall.get(5, TimeUnit.SECONDS).successCount());
        } finally {
            pool.shutdownNow();
        }
        verify(gateway, times(3)).dispatch(any());
    }

    @Test
    void directDispatchRequiresReportPermission() {
        // user3 没有应收报表权限：报表页手工派单同样被拦下，表现为报表不存在
        ApiException e = assertThrows(ApiException.class,
                () -> service.dispatchDirect(TestCatalog.USER3, "receivable", List.of("1")));
        assertEquals(404, e.getCode());
        verify(gateway, never()).dispatch(any());
    }
}
