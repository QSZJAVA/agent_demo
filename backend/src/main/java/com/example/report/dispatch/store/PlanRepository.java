package com.example.report.dispatch.store;

import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 待确认清单的持久化。状态迁移一律是带原状态条件的更新（CAS），返回是否迁移成功。
 */
public interface PlanRepository {

    void insert(DispatchPlan plan, List<DispatchPlanItem> items);

    Optional<DispatchPlan> find(String planId);

    Optional<DispatchPlan> findByIdempotencyKey(String tenantId, String idempotencyKey);

    List<DispatchPlan> findAll(Collection<String> planIds);

    List<DispatchPlanItem> items(String planId);

    default List<DispatchPlanItem> page(String planId, int offset, int size) {
        List<DispatchPlanItem> all = items(planId);
        return offset >= all.size() ? List.of() : all.subList(offset, Math.min(all.size(), offset + size));
    }

    /** 本会话当前 PENDING 的清单 */
    List<DispatchPlan> pending(String conversationId);

    /** 基于某个预览、当前 PENDING 的清单 */
    List<DispatchPlan> pendingByPreview(String previewId);

    /** Another plan from the same preview has already been claimed. */
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

    /** 认领执行：仅当清单仍是 PENDING 且未过期时迁移到 EXECUTING，并记录确认人 */
    boolean claim(String planId, String confirmedBy, LocalDateTime now);

    /** 只认领已经收尾的清单，用于重试明确失败的条目。 */
    boolean claimRetry(String planId, LocalDateTime now);

    void updateItem(DispatchPlanItem item);

    /** 核对结果只可将仍未知的条目更新一次；旧核对请求不能覆盖后续重试结果。 */
    boolean resolveUnknownItem(DispatchPlanItem item, String expectedStatus, long executionVersion);

    /** EXECUTING → EXECUTED，写入成功 / 失败条数 */
    boolean finish(String planId, int successCount, int failedCount, LocalDateTime now);

    boolean finishReview(String planId, long executionVersion, int successCount, int failedCount, LocalDateTime now);

    default void touchExecuting(String planId, LocalDateTime now) { }

    default List<DispatchPlan> staleExecuting(LocalDateTime cutoff) { return List.of(); }

    default boolean markStaleForReview(String planId, LocalDateTime cutoff, LocalDateTime now) { return false; }
}
