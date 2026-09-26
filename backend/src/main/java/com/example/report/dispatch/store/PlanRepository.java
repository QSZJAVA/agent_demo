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

    /** 本会话当前 PENDING 的清单 */
    List<DispatchPlan> pending(String conversationId);

    /** 基于某个预览、当前 PENDING 的清单 */
    List<DispatchPlan> pendingByPreview(String previewId);

    /** A started or uncertain execution cannot be bypassed by creating another plan from its preview. */
    boolean hasStartedByPreview(String previewId);

    List<DispatchPlan> byConversation(String conversationId);

    boolean transition(String planId, String fromStatus, String toStatus, String reason, LocalDateTime now);

    /** 认领执行：仅当清单仍是 PENDING 且未过期时迁移到 EXECUTING，并记录确认人 */
    boolean claim(String planId, String confirmedBy, LocalDateTime now);

    void updateItem(DispatchPlanItem item);

    /** EXECUTING → EXECUTED，写入成功 / 失败条数 */
    boolean finish(String planId, int successCount, int failedCount, LocalDateTime now);
}
