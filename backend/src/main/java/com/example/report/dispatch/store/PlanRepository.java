package com.example.report.dispatch.store;

import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 待确认清单的持久化。状态迁移一律是带原状态条件的更新（CAS），返回是否迁移成功。
 */
public interface PlanRepository {

    void insert(DispatchPlan plan, List<DispatchPlanItem> items);

    Optional<DispatchPlan> find(String planId);

    Optional<DispatchPlan> findByIdempotencyKey(String tenantId, String idempotencyKey);

    default List<DispatchPlan> manualPlans(String tenantId, String userId, String reportId, int offset, int size) {
        throw new UnsupportedOperationException("手工清单查询尚未实现");
    }

    /** 仅释放从未发送过的终态手工草稿；已发送记录永远保留原幂等键。 */
    default boolean retireManualDraftKey(String planId) { return false; }

    List<DispatchPlan> findAll(Collection<String> planIds);

    List<DispatchPlanItem> items(String planId);

    default List<DispatchPlanItem> page(String planId, int offset, int size) {
        List<DispatchPlanItem> all = items(planId);
        return offset >= all.size() ? List.of() : all.subList(offset, Math.min(all.size(), offset + size));
    }

    /** 本会话当前 PENDING 的清单*/
    List<DispatchPlan> pending(String conversationId);

    /** 基于某个预览、当前 PENDING 的清单 */
    List<DispatchPlan> pendingByPreview(String previewId);

    /** Another plan from the same preview has already been claimed.*/
    default boolean hasOtherStartedByPreview(String previewId, String planId) { return false; }

    /** A started or uncertain execution cannot be bypassed by creating another plan from its preview. */
    boolean hasStartedByPreview(String previewId);

    default boolean hasUnsettledByConversation(String conversationId) {
        return conversationId != null && byConversation(conversationId).stream()
                .anyMatch(p -> DispatchPlan.EXECUTING.equals(p.getStatus())
                        || DispatchPlan.REVIEW_REQUIRED.equals(p.getStatus()));
    }

    List<DispatchPlan> byConversation(String conversationId);

    boolean transition(String planId, String fromStatus, String toStatus, String reason, LocalDateTime now);

    /** 认领执行：PENDING 且未过期时迁移到 EXECUTING，记录确认人并原子返回本次执行版本；失败返回 empty。*/
    Optional<Long> claim(String planId, String confirmedBy, LocalDateTime now);

    /** 只认领已经收尾的清单，原子返回本次认领的执行版本；失败返回 empty。 */
    Optional<Long> claimRetry(String planId, LocalDateTime now);

    /** An asynchronously accepted retry must remain bound to its original plan version.*/
    default Optional<Long> claimRetry(String planId, long expectedVersion, LocalDateTime now) {
        if (find(planId).filter(p -> java.util.Objects.equals(p.getExecutionVersion(), expectedVersion)).isEmpty()) return Optional.empty();
        return claimRetry(planId, now);
    }

    /** Save only while this execution round still owns the plan. */
    void updateItem(DispatchPlanItem item, long executionVersion);

    boolean isExecuting(String planId, long executionVersion);

    /** Hold the plan row lock through the external send so recovery cannot revoke it between check and send.*/
    <T> T withExecutionRight(String planId, long executionVersion, Supplier<T> action);

    /** 核对结果只可将仍未知的条目更新一次；旧核对请求不能覆盖后续重试结果。 */
    boolean resolveUnknownItem(DispatchPlanItem item, String expectedStatus, long executionVersion);

    /** EXECUTING → EXECUTED，写入成功 / 失败条数*/
    boolean finish(String planId, long executionVersion, int successCount, int failedCount, LocalDateTime now);

    boolean transitionExecution(String planId, long executionVersion, String toStatus, String reason, LocalDateTime now);

    boolean finishReview(String planId, long executionVersion, int successCount, int failedCount, LocalDateTime now);

    void touchExecuting(String planId, long executionVersion, LocalDateTime now);

    default List<DispatchPlan> staleExecuting(LocalDateTime cutoff) { return List.of(); }

    default boolean markStaleForReview(String planId, LocalDateTime cutoff, LocalDateTime now) { return false; }
}
