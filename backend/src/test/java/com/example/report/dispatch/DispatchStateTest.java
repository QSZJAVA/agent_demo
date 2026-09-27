package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPreview;
import com.example.report.rule.Candidate;
import com.example.report.support.DispatchHarness;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.EXPENSE;
import static com.example.report.support.TestCatalog.RECEIVABLE;
import static com.example.report.support.TestCatalog.SALES;
import static com.example.report.support.TestCatalog.USER1;
import static com.example.report.support.TestCatalog.USER3;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 预览与待确认清单的服务端状态机（P0-07）与统一请求协议（P0-05）
 */
class DispatchStateTest {

    private final Candidate sale = candidate(SALES, "1", "SO2026001", "A", "服务器");
    private final Candidate sale2 = candidate(SALES, "2", "SO2026002", "A", "交换机");
    private final Candidate expense = candidate(EXPENSE, "1", "EXP-2026-0001", "A", "差旅费");
    private final DispatchHarness h = new DispatchHarness().put(SALES, sale, sale2).put(EXPENSE, expense);
    private final CardStateService cards = new CardStateService(h.previews, h.plans, h.store.previews(), h.store.plans());

    private PreviewOutcome preview(String conversationId, String reportQuery) {
        return h.previews.preview(USER1, conversationId, new PreviewCommand(null, "api", reportQuery, null, null, null, null));
    }

    private DispatchPreview row(String previewId) {
        return h.store.previews().find(previewId).orElseThrow();
    }

    private DispatchPlan planRow(String planId) {
        return h.store.plans().find(planId).orElseThrow();
    }

    @Test
    void previewRecordsTheUnifiedRequestAndItsVersions() {
        PreviewSnapshot snapshot = preview("c1", "销售台账").snapshot();
        DispatchPreview p = snapshot.preview();
        assertEquals(DispatchPreview.ACTIVE, p.getStatus());
        assertEquals("T001", p.getTenantId());
        assertEquals(List.of(SALES), snapshot.reportIds());
        assertEquals(USER1.permissionVersion(), p.getPermissionVersion());
        assertEquals("rules-v1", p.getRuleVersion());
        assertNotNull(p.getCatalogVersion());
        Map<String, Object> query = snapshot.query();
        assertEquals("PREVIEW", query.get("operation"));
        assertEquals("销售台账", query.get("reportQuery"));
        assertEquals("ALIAS", query.get("matchType"));
        assertEquals(List.of(SALES), query.get("reportIds"));
        assertEquals(Map.of(SALES, 1), query.get("catalogVersions"));
        assertEquals(2, p.getTotalCount());
    }

    @Test
    void emptyResultIsStillAnExplicitZeroPreview() {
        // T-PREVIEW-02
        PreviewOutcome outcome = preview("c1", "应收报表");
        assertEquals(PreviewOutcome.Status.OK, outcome.status());
        assertEquals(0, outcome.snapshot().preview().getTotalCount());
        assertEquals(DispatchPreview.ACTIVE, outcome.snapshot().preview().getStatus());
        assertEquals(List.of(RECEIVABLE), outcome.snapshot().reportIds());
    }

    @Test
    void newPreviewSupersedesTheOldOneAndExpiresItsPendingPlan() {
        // T-PREVIEW-07：新预览使同会话旧预览变为 SUPERSEDED；5.3.3：生成新预览时待确认清单失效
        String first = preview("c1", null).snapshot().preview().getId();
        String plan = h.plans.create(USER1, "c1", null, List.of(), null).plan().getId();
        PreviewOutcome second = preview("c1", "销售报表");
        assertEquals(List.of(first), second.supersededPreviewIds());
        assertEquals(List.of(plan), second.expiredPlanIds());
        assertEquals(DispatchPreview.SUPERSEDED, row(first).getStatus());
        assertEquals(StateReason.NEW_PREVIEW, row(first).getStatusReason());
        assertEquals(DispatchPlan.EXPIRED, planRow(plan).getStatus());
        ApiException e = assertThrows(ApiException.class, () -> h.plans.create(USER1, "c1", first, List.of(), null));
        assertTrue(e.getMessage().contains("已作废"));
    }

    @Test
    void freshExecutionHeartbeatPreventsStaleRecovery() {
        String previewId = preview("heartbeat", "销售报表").snapshot().preview().getId();
        String planId = h.plans.create(USER1, "heartbeat", previewId, List.of(), null).plan().getId();
        LocalDateTime now = LocalDateTime.now();
        assertTrue(h.store.plans().claim(planId, USER1.userId(), now.minusMinutes(10)));
        LocalDateTime cutoff = now.minusMinutes(5);
        assertEquals(1, h.store.plans().staleExecuting(cutoff).size());
        h.store.plans().touchExecuting(planId, now);
        assertFalse(h.store.plans().markStaleForReview(planId, cutoff, now));
        assertEquals(DispatchPlan.EXECUTING, planRow(planId).getStatus());
    }

    @Test
    void cancelledJobCannotSupersedeExistingPreviewOrPlan() {
        String first = preview("c1", "销售报表").snapshot().preview().getId();
        String plan = h.plans.create(USER1, "c1", first, List.of(), null).plan().getId();
        PreviewCommand command = new PreviewCommand(null, "selection", "费用报表", null, null, null, null);

        assertThrows(ApiException.class, () -> h.previews.preview(USER1, "c1", command,
                scanned -> { }, id -> { throw new ApiException(409, "查询任务已取消"); }));
        assertEquals(DispatchPreview.ACTIVE, row(first).getStatus());
        assertEquals(DispatchPlan.PENDING, planRow(plan).getStatus());
    }

    @Test
    void previewsOfDifferentConversationsAreIndependent() {
        // T-PREVIEW-08
        String a = preview("c1", null).snapshot().preview().getId();
        String b = preview("c2", null).snapshot().preview().getId();
        preview("c1", "销售报表");
        assertEquals(DispatchPreview.SUPERSEDED, row(a).getStatus());
        assertEquals(DispatchPreview.ACTIVE, row(b).getStatus());
        assertEquals(PlanSnapshot.class, h.plans.create(USER1, "c2", null, List.of(), null).getClass());
    }

    @Test
    void onlyOnePendingPlanPerConversation() {
        // 5.3.2：同一会话同一时刻只能有一份 PENDING 清单
        preview("c1", null);
        String first = h.plans.create(USER1, "c1", null, List.of(), null).plan().getId();
        PlanSnapshot second = h.plans.create(USER1, "c1", null, List.of("SO2026002"), null);
        assertEquals(List.of(first), second.expiredPlanIds());
        assertEquals(DispatchPlan.EXPIRED, planRow(first).getStatus());
        assertEquals(StateReason.NEW_PLAN, planRow(first).getStatusReason());
        assertEquals(List.of("SO2026002"), second.excluded());
        assertEquals(2, second.items().size());
    }

    @Test
    void excludesMustComeFromThePreview() {
        // T-DISPATCH-01
        preview("c1", null);
        ApiException e = assertThrows(ApiException.class,
                () -> h.plans.create(USER1, "c1", null, List.of("so2026002", "INV-9999"), null));
        assertTrue(e.getMessage().contains("INV-9999"));
        assertFalse(e.getMessage().contains("so2026002"), "单据号不区分大小写");
        assertThrows(ApiException.class, () -> h.plans.create(USER1, "c1", null,
                List.of("SO2026001", "SO2026002", "EXP-2026-0001"), null), "全部排除后没有需要派单的记录");
    }

    @Test
    void idempotencyKeyReturnsTheFirstPlan() {
        preview("c1", null);
        PlanSnapshot first = h.plans.create(USER1, "c1", null, List.of(), "key-1");
        PlanSnapshot again = h.plans.create(USER1, "c1", null, List.of("SO2026001"), "key-1");
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(first.plan().getId(), again.plan().getId());
        assertEquals(409, assertThrows(ApiException.class,
                () -> h.plans.create(TestCatalog.USER2, null, null, List.of(), "key-1")).getCode(), "别人的幂等键不能复用");
    }

    @Test
    void cancelIsIdempotentButExecutedPlansCannotBeCancelled() {
        preview("c1", null);
        String planId = h.plans.create(USER1, "c1", null, List.of(), null).plan().getId();
        assertEquals(DispatchPlan.CANCELLED, h.plans.cancel(USER1, planId).getStatus());
        assertEquals(DispatchPlan.CANCELLED, h.plans.cancel(USER1, planId).getStatus());
        assertEquals(StateReason.USER_CANCELLED, planRow(planId).getStatusReason());
        assertEquals(404, assertThrows(ApiException.class, () -> h.plans.cancel(TestCatalog.USER2, planId)).getCode());

        preview("c1", null);
        String executed = h.plans.create(USER1, "c1", null, List.of(), null).plan().getId();
        h.store.updatePlan(executed, p -> p.setStatus(DispatchPlan.EXECUTED));
        assertThrows(ApiException.class, () -> h.plans.cancel(USER1, executed));
    }

    @Test
    void ttlExpiryIsAppliedWhenTheStateIsRead() {
        String previewId = preview("c1", null).snapshot().preview().getId();
        String planId = h.plans.create(USER1, "c1", null, List.of(), null).plan().getId();
        h.store.updatePreview(previewId, p -> p.setExpiresAt(LocalDateTime.now().minusMinutes(1)));
        h.store.updatePlan(planId, p -> p.setExpiresAt(LocalDateTime.now().minusMinutes(1)));
        Map<String, CardStateService.CardState> previews = cards.previewStates(USER1, List.of(previewId));
        Map<String, CardStateService.CardState> plans = cards.planStates(USER1, List.of(planId));
        assertEquals(DispatchPreview.EXPIRED, previews.get(previewId).status());
        assertEquals(StateReason.TTL, previews.get(previewId).reason());
        assertEquals(DispatchPlan.EXPIRED, plans.get(planId).status());
        assertEquals(DispatchPreview.EXPIRED, row(previewId).getStatus(), "懒惰校验的结果要落库，换设备看到的也是过期");
    }

    @Test
    void cardStatesComeFromTheServerAndOnlyForTheOwner() {
        // T-UI-01 / T-UI-02：旧卡片作废、状态以服务端为准；别人的卡片不返回
        String old = preview("c1", null).snapshot().preview().getId();
        String plan = h.plans.create(USER1, "c1", null, List.of(), null).plan().getId();
        String fresh = preview("c1", "销售报表").snapshot().preview().getId();
        CardStateService.ConversationStates states = cards.conversationStates(USER1, "c1");
        assertEquals(DispatchPreview.SUPERSEDED, states.previews().get(old).status());
        assertNotNull(states.previews().get(old).message());
        assertEquals(DispatchPreview.ACTIVE, states.previews().get(fresh).status());
        assertEquals(DispatchPlan.EXPIRED, states.plans().get(plan).status());
        assertTrue(cards.conversationStates(TestCatalog.USER2, "c1").previews().isEmpty());
        // 规则变化后，还没被使用的有效预览在读取状态时就显示为失效
        h.ruleVersion.set("rules-v2");
        assertEquals(StateReason.RULE_CHANGED, cards.previewStates(USER1, List.of(fresh)).get(fresh).reason());
    }

    @Test
    void explicitReportIdsAreCheckedAgainstPermissions() {
        // 选择卡片 / REST 直接传 report_id：同样按权限校验，无权限与不存在表现一致
        ApiException e = assertThrows(ApiException.class, () -> h.previews.preview(USER3, null,
                new PreviewCommand(null, "selection", null, List.of(RECEIVABLE), null, null, null)));
        assertEquals(404, e.getCode());
        assertEquals(404, assertThrows(ApiException.class, () -> h.previews.preview(USER1, null,
                new PreviewCommand(null, "selection", null, List.of("report_sales"), null, null, null))).getCode());
    }

    @Test
    void companyFilterMustStayInsideTheUsersScope() {
        assertThrows(ApiException.class, () -> h.previews.preview(USER1, null,
                new PreviewCommand(null, "api", null, null, new PreviewCommand.Filters("C"), null, null)));
        PreviewSnapshot own = h.previews.preview(USER1, null,
                new PreviewCommand(null, "api", null, null, new PreviewCommand.Filters("a"), null, null)).snapshot();
        assertEquals(List.of("A"), ((Map<?, ?>) own.query().get("filters")).get("companyCodes"));
    }

    @Test
    void largePreviewPersistsAllRowsAndRequiresNarrowingBeforePlanning() {
        h.props.getPreview().setMaxItems(2);
        PreviewSnapshot large = preview("c1", null).snapshot();
        assertEquals(3, large.preview().getTotalCount());
        assertEquals(3, h.store.previews().items(large.preview().getId()).size());
        assertEquals(2, h.previews.pageOwned(USER1, large.preview().getId(), 1, 2).size());
        assertEquals(1, h.previews.pageOwned(USER1, large.preview().getId(), 2, 2).size());
        assertEquals(3, com.example.report.agent.PreviewPayload.of(large, h.catalogService).byReport()
                .stream().mapToInt(com.example.report.agent.PreviewPayload.ReportCount::count).sum());
        ApiException e = assertThrows(ApiException.class,
                () -> h.plans.create(USER1, "c1", large.preview().getId(), List.of(), null));
        assertTrue(e.getMessage().contains("缩小范围"));
        assertEquals(PreviewOutcome.Status.OK, preview("c1", "销售报表").status());
    }

    @Test
    void oldReviewCannotFinishAfterRetryReturnsToReviewRequired() {
        preview("c1", null);
        String id = h.plans.create(USER1, "c1", null, List.of(), null).plan().getId();
        var repository = h.store.plans();
        var now = LocalDateTime.now();
        assertTrue(repository.claim(id, USER1.userId(), now));
        long firstVersion = planRow(id).getExecutionVersion();
        assertTrue(repository.transition(id, DispatchPlan.EXECUTING, DispatchPlan.REVIEW_REQUIRED, null, now));
        assertTrue(repository.finishReview(id, firstVersion, 0, 3, now));
        assertTrue(repository.claimRetry(id, now));
        assertEquals(firstVersion + 1, planRow(id).getExecutionVersion());
        assertTrue(repository.transition(id, DispatchPlan.EXECUTING, DispatchPlan.REVIEW_REQUIRED, null, now));
        assertFalse(repository.finishReview(id, firstVersion, 0, 3, now));
        assertEquals(DispatchPlan.REVIEW_REQUIRED, planRow(id).getStatus());
    }

    @Test
    void ambiguousAndUnknownReportsDoNotCreatePreviews() {
        // T-RESOLVE-03 / T-RESOLVE-08
        assertEquals(PreviewOutcome.Status.AMBIGUOUS, preview("c1", "客户对账").status());
        assertEquals(PreviewOutcome.Status.NOT_FOUND, preview("c1", "查XX").status());
        assertTrue(h.previews.latest(USER1, "c1").isEmpty());
        assertThrows(ApiException.class, () -> h.plans.create(USER1, "c1", null, List.of(), null));
    }
}
