package com.example.report.dispatch.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.dispatch.StateReason;
import com.example.report.mapper.DispatchPlanItemMapper;
import com.example.report.mapper.DispatchPlanMapper;
import com.example.report.entity.AgentMessage;
import com.example.report.agent.PlanPayload;
import com.example.report.common.JsonUtil;
import com.example.report.dispatch.PlanSnapshot;
import com.example.report.trace.TraceJournal;
import com.example.report.trace.RuleEvidence;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class MybatisPlanRepository implements PlanRepository {
    @org.springframework.beans.factory.annotation.Value("${business.remote.enabled:false}")
    private boolean remoteBusiness;

    private static final int INSERT_CHUNK = 500;

    private final DispatchPlanMapper planMapper;
    private final DispatchPlanItemMapper itemMapper;
    private final TraceJournal journal;
    private final RuleEvidence rules;

    public MybatisPlanRepository(DispatchPlanMapper planMapper, DispatchPlanItemMapper itemMapper,
                                 TraceJournal journal, RuleEvidence rules) {
        this.planMapper = planMapper;
        this.itemMapper = itemMapper;
        this.journal = journal;
        this.rules = rules;
    }

    @Override
    @Transactional
    public void insert(DispatchPlan plan, List<DispatchPlanItem> items) {
        plan.setEvidenceVersion(1);
        java.util.Map<String, String> snapshots = new java.util.HashMap<>();
        items.forEach(item -> item.setRuleSnapshot(snapshots.computeIfAbsent(
                item.getReportId() + ":" + item.getRuleId() + ":" + item.getRuleVersion(), key -> rules.capture(plan.getTenantId(), item))));
        planMapper.insert(plan);
        for (int i = 0; i < items.size(); i += INSERT_CHUNK) {
            itemMapper.insertBatch(items.subList(i, Math.min(items.size(), i + INSERT_CHUNK)));
        }
        journal.plan(plan, "CREATED");
        if (plan.getConversationId() != null) {
            AgentMessage message = new AgentMessage();
            message.setTenantId(plan.getTenantId());
            message.setUserId(plan.getUserId());
            message.setConversationId(plan.getConversationId());
            message.setPreviewId(plan.getPreviewId());
            message.setPlanId(plan.getId());
            message.setRole(AgentMessage.ROLE_CARD);
            message.setCardType("plan");
            message.setPayload(JsonUtil.toJson(PlanPayload.of(new PlanSnapshot(plan, items))));
            message.setCreatedAt(plan.getCreatedAt());
            journal.message(message, false, "card:plan:" + plan.getId());
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
    public List<DispatchPlan> manualPlans(String tenantId, String userId, String reportId, int offset, int size) {
        return planMapper.selectList(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getTenantId, tenantId).eq(DispatchPlan::getUserId, userId)
                .likeRight(DispatchPlan::getIdempotencyKey, "manual:")
                .apply("EXISTS (SELECT 1 FROM dispatch_plan_item i WHERE i.plan_id=dispatch_plan.id AND i.report_id={0})", reportId)
                .orderByDesc(DispatchPlan::getCreatedAt).orderByDesc(DispatchPlan::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    @Override
    public boolean retireManualDraftKey(String planId) {
        return planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .in(DispatchPlan::getStatus, DispatchPlan.EXPIRED, DispatchPlan.CANCELLED, DispatchPlan.EXECUTED)
                .likeRight(DispatchPlan::getIdempotencyKey, "manual:")
                .apply("NOT EXISTS (SELECT 1 FROM dispatch_plan_item i WHERE i.plan_id=dispatch_plan.id AND i.attempt_count > 0)")
                .set(DispatchPlan::getIdempotencyKey, "retired:" + planId)) == 1;
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
    @Transactional
    public boolean transition(String planId, String fromStatus, String toStatus, String reason, LocalDateTime now) {
        boolean changed = planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, fromStatus)
                .set(DispatchPlan::getStatus, toStatus)
                .set(DispatchPlan::getStatusReason, reason)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
        if (changed) journal.plan(planMapper.selectById(planId), "TRANSITION");
        return changed;
    }

    @Override
    @Transactional
    public Optional<Long> claim(String planId, String confirmedBy, LocalDateTime now) {
        int changed = planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.PENDING)
                .gt(DispatchPlan::getExpiresAt, now)
                .setSql("execution_version = execution_version + 1")
                .set(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .set(DispatchPlan::getConfirmedAt, now)
                .set(DispatchPlan::getConfirmedBy, confirmedBy)
                .set(DispatchPlan::getUpdatedAt, now));
        return claimedVersion(planId, changed);
    }

    @Override
    @Transactional
    public Optional<Long> claimRetry(String planId, LocalDateTime now) {
        int changed = planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.EXECUTED)
                .gt(DispatchPlan::getFailedCount, 0)
                .setSql("execution_version = execution_version + 1")
                .set(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .set(DispatchPlan::getUpdatedAt, now));
        return claimedVersion(planId, changed);
    }

    private Optional<Long> claimedVersion(String planId, int changed) {
        if (changed != 1) return Optional.empty();
        journal.plan(planMapper.selectById(planId), "CLAIMED");
        // UPDATE 的行锁一直持有到事务提交；必须在锁内取得本次版本，不能由调用者另读当前版本。
        return Optional.of(java.util.Objects.requireNonNull(planMapper.lockExecution(planId),
                "认领成功但未取得执行版本：" + planId));
    }

    @Override
    @Transactional
    public void updateItem(DispatchPlanItem item, long executionVersion) {
        if (!java.util.Objects.equals(planMapper.lockExecution(item.getPlanId()), executionVersion)) {
            throw new IllegalStateException("派单执行权已失效：" + item.getPlanId());
        }
        if (itemMapper.update(null, new LambdaUpdateWrapper<DispatchPlanItem>()
                .eq(DispatchPlanItem::getId, item.getId())
                .eq(DispatchPlanItem::getPlanId, item.getPlanId())
                .apply("EXISTS (SELECT 1 FROM dispatch_plan p WHERE p.id = dispatch_plan_item.plan_id "
                        + "AND p.status = {0} AND p.execution_version = {1})", DispatchPlan.EXECUTING, executionVersion)
                .set(DispatchPlanItem::getStatus, item.getStatus())
                .set(DispatchPlanItem::getAttemptCount, item.getAttemptCount())
                .set(DispatchPlanItem::getExternalRequestId, item.getExternalRequestId())
                .set(DispatchPlanItem::getErrorCode, item.getErrorCode())
                .set(DispatchPlanItem::getErrorMessage, item.getErrorMessage())
                .set(DispatchPlanItem::getUpdatedAt, item.getUpdatedAt())) != 1) {
            throw new IllegalStateException("派单执行权已失效或条目结果未保存：" + item.getId());
        }
    }

    @Override
    public boolean isExecuting(String planId, long executionVersion) {
        return planMapper.selectCount(new LambdaQueryWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId).eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .eq(DispatchPlan::getExecutionVersion, executionVersion)) == 1;
    }

    @Override
    @Transactional
    public <T> T withExecutionRight(String planId, long executionVersion, Supplier<T> action) {
        // The business service holds this row lock during remote mutation. Holding it here
        // would deadlock the two processes; the remote service validates the same fencing version.
        if(remoteBusiness) {
            if(!isExecuting(planId,executionVersion)) throw new IllegalStateException("派单执行权已失效："+planId);
            return action.get();
        }
        if (!java.util.Objects.equals(planMapper.lockExecution(planId), executionVersion)) {
            throw new IllegalStateException("派单执行权已失效：" + planId);
        }
        return action.get();
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
    @Transactional
    public boolean finish(String planId, long executionVersion, int successCount, int failedCount, LocalDateTime now) {
        boolean changed = planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId).eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .eq(DispatchPlan::getExecutionVersion, executionVersion)
                .set(DispatchPlan::getStatus, DispatchPlan.EXECUTED)
                .set(DispatchPlan::getStatusReason, null)
                .set(DispatchPlan::getSuccessCount, successCount)
                .set(DispatchPlan::getFailedCount, failedCount)
                .set(DispatchPlan::getFinishedAt, now).set(DispatchPlan::getUpdatedAt, now)) == 1;
        if (changed) journal.plan(planMapper.selectById(planId), "FINISH");
        return changed;
    }

    @Override
    @Transactional
    public boolean transitionExecution(String planId, long executionVersion, String toStatus, String reason, LocalDateTime now) {
        boolean changed = planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId).eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .eq(DispatchPlan::getExecutionVersion, executionVersion)
                .set(DispatchPlan::getStatus, toStatus).set(DispatchPlan::getStatusReason, reason)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
        if (changed) journal.plan(planMapper.selectById(planId), "TRANSITIONEXECUTION");
        return changed;
    }

    @Override
    @Transactional
    public boolean finishReview(String planId, long executionVersion, int successCount, int failedCount, LocalDateTime now) {
        boolean changed = planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.REVIEW_REQUIRED)
                .eq(DispatchPlan::getExecutionVersion, executionVersion)
                .set(DispatchPlan::getStatus, DispatchPlan.EXECUTED)
                .set(DispatchPlan::getStatusReason, null)
                .set(DispatchPlan::getSuccessCount, successCount)
                .set(DispatchPlan::getFailedCount, failedCount)
                .set(DispatchPlan::getFinishedAt, now)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
        if (changed) journal.plan(planMapper.selectById(planId), "FINISHREVIEW");
        return changed;
    }

    @Override
    public void touchExecuting(String planId, long executionVersion, LocalDateTime now) {
        planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId).eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .eq(DispatchPlan::getExecutionVersion, executionVersion)
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
    @Transactional
    public boolean markStaleForReview(String planId, LocalDateTime cutoff, LocalDateTime now) {
        boolean changed = planMapper.update(null, new LambdaUpdateWrapper<DispatchPlan>()
                .eq(DispatchPlan::getId, planId)
                .eq(DispatchPlan::getStatus, DispatchPlan.EXECUTING)
                .lt(DispatchPlan::getUpdatedAt, cutoff)
                .set(DispatchPlan::getStatus, DispatchPlan.REVIEW_REQUIRED)
                .setSql("execution_version = execution_version + 1")
                .set(DispatchPlan::getStatusReason, StateReason.EXECUTION_INTERRUPTED)
                .set(DispatchPlan::getUpdatedAt, now)) == 1;
        if (changed) journal.plan(planMapper.selectById(planId), "MARKSTALEFORREVIEW");
        return changed;
    }
}
