package com.example.report.dispatch.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.dispatch.StateReason;
import com.example.report.mapper.DispatchPlanItemMapper;
import com.example.report.mapper.DispatchPlanMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisPlanRepository implements PlanRepository {

    private static final int INSERT_CHUNK = 500;

    private final DispatchPlanMapper planMapper;
    private final DispatchPlanItemMapper itemMapper;

    public MybatisPlanRepository(DispatchPlanMapper planMapper, DispatchPlanItemMapper itemMapper) {
        this.planMapper = planMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public void insert(DispatchPlan plan, List<DispatchPlanItem> items) {
        planMapper.insert(plan);
        for (int i = 0; i < items.size(); i += INSERT_CHUNK) {
            itemMapper.insertBatch(items.subList(i, Math.min(items.size(), i + INSERT_CHUNK)));
        }
    }

    @Override
    public Optional<DispatchPlan> find(String planId) {
        return planId == null ? Optional.empty() : Optional.ofNullable(planMapper.selectById(planId));
    }

    @Override
    public Optional<DispatchPlan> findByIdempotencyKey(String tenantId, String idempotencyKey) {
        return planMapper.selectList(new LambdaQueryWrapper<DispatchPlan>()
                        .eq(DispatchPlan::getTenantId, tenantId)
                        .eq(DispatchPlan::getIdempotencyKey, idempotencyKey))
                .stream().findFirst();
    }

    @Override
    public List<DispatchPlan> findAll(Collection<String> planIds) {
        return planIds.isEmpty() ? List.of() : planMapper.selectByIds(planIds);
    }

    @Override
    public List<DispatchPlanItem> items(String planId) {
        return itemMapper.selectList(new LambdaQueryWrapper<DispatchPlanItem>()
                .eq(DispatchPlanItem::getPlanId, planId)
                .orderByAsc(DispatchPlanItem::getSeq));
    }

    @Override
    public List<DispatchPlanItem> page(String planId, int offset, int size) {
        return itemMapper.selectList(new LambdaQueryWrapper<DispatchPlanItem>()
                .eq(DispatchPlanItem::getPlanId, planId)
                .orderByAsc(DispatchPlanItem::getSeq)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    @Override
    public List<DispatchPlan> pending(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        return planMapper.selectList(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getConversationId, conversationId)
                .eq(DispatchPlan::getStatus, DispatchPlan.PENDING));
    }

    @Override
    public List<DispatchPlan> pendingByPreview(String previewId) {
        return planMapper.selectList(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getPreviewId, previewId)
                .eq(DispatchPlan::getStatus, DispatchPlan.PENDING));
    }

    @Override
    public List<DispatchPlan> byConversation(String conversationId) {
        return planMapper.selectList(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getConversationId, conversationId)
                .orderByAsc(DispatchPlan::getCreatedAt));
    }

    @Override
    public boolean hasStartedByPreview(String previewId) {
        return planMapper.selectCount(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getPreviewId, previewId)
                .in(DispatchPlan::getStatus, DispatchPlan.EXECUTING, DispatchPlan.EXECUTED, DispatchPlan.REVIEW_REQUIRED)) > 0;
    }

    @Override
    public boolean hasOtherStartedByPreview(String previewId, String planId) {
        return planMapper.selectCount(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getPreviewId, previewId)
                .ne(DispatchPlan::getId, planId)
                .in(DispatchPlan::getStatus, DispatchPlan.EXECUTING, DispatchPlan.EXECUTED,
                        DispatchPlan.REVIEW_REQUIRED)) > 0;
    }

    @Override
    public boolean hasUnsettledByConversation(String conversationId) {
        return conversationId != null && planMapper.selectCount(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getConversationId, conversationId)
                .in(DispatchPlan::getStatus, DispatchPlan.EXECUTING, DispatchPlan.REVIEW_REQUIRED)) > 0;
    }

    @Override
    public boolean transition(String planId, String fromStatus, String toStatus, String reason, LocalDateTime now) {
        return planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, fromStatus)
                .set(DispatchPlan::getStatus, toStatus)
                .set(DispatchPlan::getStatusReason, reason)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
    }

    @Override
    public boolean claim(String planId, String confirmedBy, LocalDateTime now) {
        return planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.PENDING)
                .gt(DispatchPlan::getExpiresAt, now)
                .setSql("execution_version = execution_version + 1")
                .set(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .set(DispatchPlan::getConfirmedAt, now)
                .set(DispatchPlan::getConfirmedBy, confirmedBy)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
    }

    @Override
    public boolean claimRetry(String planId, LocalDateTime now) {
        return planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.EXECUTED)
                .gt(DispatchPlan::getFailedCount, 0)
                .setSql("execution_version = execution_version + 1")
                .set(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
    }

    @Override
    public void updateItem(DispatchPlanItem item) {
        if (itemMapper.update(null, new LambdaUpdateWrapper<DispatchPlanItem>()
                .eq(DispatchPlanItem::getId, item.getId())
                .eq(DispatchPlanItem::getPlanId, item.getPlanId())
                .set(DispatchPlanItem::getStatus, item.getStatus())
                .set(DispatchPlanItem::getAttemptCount, item.getAttemptCount())
                .set(DispatchPlanItem::getExternalRequestId, item.getExternalRequestId())
                .set(DispatchPlanItem::getErrorCode, item.getErrorCode())
                .set(DispatchPlanItem::getErrorMessage, item.getErrorMessage())
                .set(DispatchPlanItem::getUpdatedAt, item.getUpdatedAt())) != 1) {
            throw new IllegalStateException("派单条目结果未保存：" + item.getId());
        }
    }

    @Override
    public boolean resolveUnknownItem(DispatchPlanItem item, String expectedStatus, long executionVersion) {
        return itemMapper.update(null, new LambdaUpdateWrapper<DispatchPlanItem>()
                .eq(DispatchPlanItem::getId, item.getId())
                .eq(DispatchPlanItem::getPlanId, item.getPlanId())
                .eq(DispatchPlanItem::getStatus, expectedStatus)
                .eq(DispatchPlanItem::getAttemptCount, item.getAttemptCount())
                .apply("EXISTS (SELECT 1 FROM dispatch_plan p WHERE p.id = dispatch_plan_item.plan_id "
                        + "AND p.status = {0} AND p.execution_version = {1})", DispatchPlan.REVIEW_REQUIRED, executionVersion)
                .set(DispatchPlanItem::getStatus, item.getStatus())
                .set(DispatchPlanItem::getExternalRequestId, item.getExternalRequestId())
                .set(DispatchPlanItem::getErrorCode, item.getErrorCode())
                .set(DispatchPlanItem::getErrorMessage, item.getErrorMessage())
                .set(DispatchPlanItem::getUpdatedAt, item.getUpdatedAt())) == 1;
    }

    @Override
    public boolean finish(String planId, int successCount, int failedCount, LocalDateTime now) {
        return planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .set(DispatchPlan::getStatus, DispatchPlan.EXECUTED)
                .set(DispatchPlan::getStatusReason, null)
                .set(DispatchPlan::getSuccessCount, successCount)
                .set(DispatchPlan::getFailedCount, failedCount)
                .set(DispatchPlan::getFinishedAt, now)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
    }

    @Override
    public boolean finishReview(String planId, long executionVersion, int successCount, int failedCount, LocalDateTime now) {
        return planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.REVIEW_REQUIRED)
                .eq(DispatchPlan::getExecutionVersion, executionVersion)
                .set(DispatchPlan::getStatus, DispatchPlan.EXECUTED)
                .set(DispatchPlan::getStatusReason, null)
                .set(DispatchPlan::getSuccessCount, successCount)
                .set(DispatchPlan::getFailedCount, failedCount)
                .set(DispatchPlan::getFinishedAt, now)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
    }

    @Override
    public void touchExecuting(String planId, LocalDateTime now) {
        planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .set(DispatchPlan::getUpdatedAt, now));
    }

    @Override
    public List<DispatchPlan> staleExecuting(LocalDateTime cutoff) {
        return planMapper.selectList(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .lt(DispatchPlan::getUpdatedAt, cutoff)
                .last("LIMIT 100"));
    }

    @Override
    public boolean markStaleForReview(String planId, LocalDateTime cutoff, LocalDateTime now) {
        return planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .lt(DispatchPlan::getUpdatedAt, cutoff)
                .set(DispatchPlan::getStatus, DispatchPlan.REVIEW_REQUIRED)
                .set(DispatchPlan::getStatusReason, StateReason.EXECUTION_INTERRUPTED)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
    }
}
