package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.dispatch.store.PreviewRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 待确认清单：只能来自本会话当前有效的一份预览（归属、状态、版本都要校验），排除项必须在预览结果里；
 * 同一会话同一时刻只有一份 PENDING 清单，新清单生成时旧清单失效。
 */
@Slf4j
@Service
public class PlanService {

    private final PreviewService previewService;
    private final PreviewRepository previews;
    private final PlanRepository plans;
    private final DispatchVersionService versions;
    private final AgentProperties props;
    private final TransactionOperations tx;

    public PlanService(PreviewService previewService, PreviewRepository previews, PlanRepository plans,
                       DispatchVersionService versions, AgentProperties props, TransactionOperations tx) {
        this.previewService = previewService;
        this.previews = previews;
        this.plans = plans;
        this.versions = versions;
        this.props = props;
        this.tx = tx;
    }

    /**
     * @param previewId      为空时使用本会话最近一次预览
     * @param excludes       排除的单据号（不区分大小写），必须都在预览结果中
     * @param idempotencyKey 可选：同租户同一个键只生成一份清单，重复请求返回第一次的清单
     */
    public PlanSnapshot create(CurrentUser user, String conversationId, String previewId, List<String> excludes,
                               String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<DispatchPlan> existing = plans.findByIdempotencyKey(user.tenantId(), idempotencyKey.trim());
            if (existing.isPresent()) {
                DispatchPlan plan = existing.get();
                if (!PermissionService.owns(user, plan.getTenantId(), plan.getUserId())) {
                    throw new ApiException(409, "幂等键已被使用，请更换后重试");
                }
                previewService.requireReadable(user, previews.find(plan.getPreviewId())
                        .orElseThrow(() -> ApiException.notFound("原预览不存在")));
                return new PlanSnapshot(refresh(user, plan), plans.items(plan.getId()), List.of(), true);
            }
        }
        DispatchPreview preview = usablePreview(user, conversationId, previewId);
        if (preview.getTotalCount() > props.getPreview().getMaxItems()) {
            throw new ApiException("该预览记录过多，请按报表或公司缩小范围后再生成派单清单");
        }
        List<DispatchPreviewItem> previewItems = previews.items(preview.getId());

        Set<String> excludeKeys = new LinkedHashSet<>();
        if (excludes != null) {
            excludes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(e -> excludeKeys.add(e.toUpperCase(Locale.ROOT)));
        }
        Map<String, DispatchPreviewItem> byDocNo = new LinkedHashMap<>();
        previewItems.stream().filter(i -> i.getDocNo() != null)
                .forEach(i -> byDocNo.putIfAbsent(i.getDocNo().toUpperCase(Locale.ROOT), i));
        List<String> unmatched = excludes == null ? List.of() : excludes.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .filter(e -> !byDocNo.containsKey(e.toUpperCase(Locale.ROOT)))
                .distinct().toList();
        if (!unmatched.isEmpty()) {
            throw new ApiException("以下单据号不在预览结果中，请确认：" + String.join("、", unmatched));
        }
        List<DispatchPreviewItem> remaining = previewItems.stream()
                .filter(i -> i.getDocNo() == null || !excludeKeys.contains(i.getDocNo().toUpperCase(Locale.ROOT)))
                .toList();
        List<String> excluded = excludeKeys.stream().map(k -> byDocNo.get(k).getDocNo()).toList();
        if (remaining.isEmpty()) {
            throw new ApiException("排除之后没有需要派单的记录");
        }

        LocalDateTime now = LocalDateTime.now();
        DispatchPlan plan = new DispatchPlan();
        plan.setId(JsonUtil.newId());
        plan.setPreviewId(preview.getId());
        plan.setTenantId(user.tenantId());
        plan.setUserId(user.userId());
        plan.setConversationId(preview.getConversationId());
        plan.setStatus(DispatchPlan.PENDING);
        plan.setExcludeJson(JsonUtil.toJson(excluded));
        plan.setItemCount(remaining.size());
        plan.setSuccessCount(0);
        plan.setFailedCount(0);
        plan.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? plan.getId() : idempotencyKey.trim());
        plan.setCreatedAt(now);
        LocalDateTime planExpiry = now.plusMinutes(props.getPlan().getTtlMinutes());
        plan.setExpiresAt(planExpiry.isBefore(preview.getExpiresAt()) ? planExpiry : preview.getExpiresAt());
        plan.setUpdatedAt(now);
        List<DispatchPlanItem> items = new ArrayList<>(remaining.size());
        for (int i = 0; i < remaining.size(); i++) {
            items.add(toItem(plan.getId(), i, remaining.get(i), now));
        }
        List<String> expired = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            previews.lockConversation(preview.getConversationId());
            previews.lockPreview(preview.getId());
            // 加锁后再确认一次：等锁期间同会话可能已经生成了新预览
            DispatchPreview current = previews.find(preview.getId()).orElseThrow();
            if (plans.hasStartedByPreview(current.getId())) {
                throw new ApiException(409, "该预览已有执行中的、已执行或待核对的清单，请先核对原清单");
            }
            if (plans.hasUnsettledByConversation(current.getConversationId())) {
                throw new ApiException(409, "本会话还有执行中或待核对的清单，请先等待或核对结果");
            }
            if (!DispatchPreview.ACTIVE.equals(current.getStatus())) {
                throw new ApiException(rejection(current));
            }
            String invalid = versions.verify(user, current);
            if (invalid != null) {
                throw new ApiException("预览已失效：" + StateReason.message(invalid));
            }
            for (DispatchPlan old : plans.pendingByPreview(current.getId())) {
                if (plans.transition(old.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED, StateReason.NEW_PLAN, now)) {
                    expired.add(old.getId());
                }
            }
            if (preview.getConversationId() != null) {
                for (DispatchPlan old : plans.pending(preview.getConversationId())) {
                    if (!expired.contains(old.getId()) && plans.transition(old.getId(), DispatchPlan.PENDING,
                            DispatchPlan.EXPIRED, StateReason.NEW_PLAN, now)) expired.add(old.getId());
                }
            }
            plans.insert(plan, items);
        });
        return new PlanSnapshot(plan, items, expired, false);
    }

    /** 当前用户的清单，读取时做懒惰校验；不归属当前用户按不存在处理 */
    public PlanSnapshot getOwned(CurrentUser user, String planId) {
        DispatchPlan plan = findOwned(user, planId)
                .orElseThrow(() -> ApiException.notFound("待确认清单不存在或已过期，请重新预览"));
        plan = refresh(user, plan);
        previewService.requireReadable(user, previews.find(plan.getPreviewId())
                .orElseThrow(() -> ApiException.notFound("原预览不存在")));
        return new PlanSnapshot(plan, plans.items(plan.getId()));
    }

    public List<DispatchPlanItem> pageOwned(CurrentUser user, String planId, int page, int size) {
        DispatchPlan plan = findOwned(user, planId)
                .orElseThrow(() -> ApiException.notFound("待确认清单不存在"));
        previewService.requireReadable(user, previews.find(plan.getPreviewId())
                .orElseThrow(() -> ApiException.notFound("预览不存在")));
        if (page < 1 || size < 1 || size > 100) throw new ApiException("页码或每页条数无效（每页最多 100 条）");
        long offset = ((long) page - 1) * size;
        return offset > Integer.MAX_VALUE ? List.of() : plans.page(planId, (int) offset, size);
    }

    public Optional<DispatchPlan> findOwned(CurrentUser user, String planId) {
        return plans.find(planId).filter(p -> PermissionService.owns(user, p.getTenantId(), p.getUserId()));
    }

    /**
     * 懒惰校验：PENDING 的清单超过有效期，或来源预览的目录 / 规则 / 权限版本变化，立即置为 EXPIRED。返回最新状态。
     */
    public DispatchPlan refresh(CurrentUser user, DispatchPlan plan) {
        if (!DispatchPlan.PENDING.equals(plan.getStatus())) {
            return plan;
        }
        LocalDateTime now = LocalDateTime.now();
        String reason;
        if (!plan.getExpiresAt().isAfter(now)) {
            reason = StateReason.TTL;
        } else {
            DispatchPreview preview = previews.find(plan.getPreviewId()).orElse(null);
            reason = preview == null ? StateReason.TTL : versions.verify(user, preview);
            if (reason != null && preview != null) {
                previewService.expire(preview, reason, now);
            }
        }
        if (reason == null) {
            return plan;
        }
        plans.transition(plan.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED, reason, now);
        return plans.find(plan.getId()).orElse(plan);
    }

    /** 取消：只有 PENDING 能取消；已取消、已失效视为已处理，重复点击不报错 */
    public DispatchPlan cancel(CurrentUser user, String planId) {
        DispatchPlan plan = refresh(user, findOwned(user, planId)
                .orElseThrow(() -> ApiException.notFound("待确认清单不存在或已过期")));
        LocalDateTime now = LocalDateTime.now();
        if (plans.transition(plan.getId(), DispatchPlan.PENDING, DispatchPlan.CANCELLED, StateReason.USER_CANCELLED, now)) {
            return plans.find(plan.getId()).orElse(plan);
        }
        DispatchPlan latest = plans.find(plan.getId()).orElse(plan);
        return switch (latest.getStatus()) {
            case DispatchPlan.EXECUTED -> throw new ApiException("该清单已执行，无法取消");
            case DispatchPlan.EXECUTING -> throw new ApiException(409, "该清单正在执行，无法取消");
            case DispatchPlan.REVIEW_REQUIRED -> throw new ApiException(409, StateReason.message(StateReason.EXECUTION_INTERRUPTED));
            default -> latest;
        };
    }

    public void expire(DispatchPlan plan, String reason, LocalDateTime now) {
        plans.transition(plan.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED, reason, now);
    }

    /** 在同一会话锁内认领执行，和新预览、新清单的作废操作串行化。 */
    public boolean claimForExecution(CurrentUser user, DispatchPlan plan, LocalDateTime now) {
        Boolean claimed = tx.execute(status -> {
            previews.lockConversation(plan.getConversationId());
            previews.lockPreview(plan.getPreviewId());
            DispatchPlan current = plans.find(plan.getId()).orElse(plan);
            if (!DispatchPlan.PENDING.equals(current.getStatus())) return false;
            if (plans.hasOtherStartedByPreview(current.getPreviewId(), current.getId())) {
                plans.transition(current.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED,
                        StateReason.NEW_PLAN, now);
                return false;
            }
            DispatchPreview preview = previews.find(current.getPreviewId()).orElse(null);
            if (preview == null || !DispatchPreview.ACTIVE.equals(preview.getStatus())) {
                plans.transition(current.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED,
                        StateReason.NEW_PREVIEW, now);
                return false;
            }
            String reason = versions.verify(user, preview);
            if (reason != null) {
                previewService.expire(preview, reason, now);
                plans.transition(current.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED, reason, now);
                return false;
            }
            return plans.claim(current.getId(), user.userId(), now);
        });
        return Boolean.TRUE.equals(claimed);
    }

    /** 生成清单用的预览：必须归属当前用户、属于本会话、仍然有效且版本一致 */
    private DispatchPreview usablePreview(CurrentUser user, String conversationId, String previewId) {
        DispatchPreview preview;
        if (previewId != null && !previewId.isBlank()) {
            preview = previewService.findOwned(user, previewId.trim())
                    .orElseThrow(() -> new ApiException("没有可用的预览快照（不存在或已过期），请先重新查询可派单记录"));
            if (conversationId != null && !conversationId.equals(preview.getConversationId())) {
                throw new ApiException("该预览已作废，请使用本会话最新的预览卡片");
            }
        } else {
            preview = previewService.latest(user, conversationId)
                    .orElseThrow(() -> new ApiException("没有可用的预览快照（不存在或已过期），请先重新查询可派单记录"));
        }
        preview = previewService.refresh(user, preview);
        if (!DispatchPreview.ACTIVE.equals(preview.getStatus())) {
            throw new ApiException(rejection(preview));
        }
        return preview;
    }

    /** 预览不能再用于生成清单时给用户的提示 */
    static String rejection(DispatchPreview preview) {
        return switch (preview.getStatus()) {
            case DispatchPreview.SUPERSEDED -> "该预览已作废，请使用本会话最新的预览卡片";
            case DispatchPreview.CONSUMED -> "该预览已经执行过派单，请重新查询可派单记录";
            default -> "预览已失效：" + StateReason.message(preview.getStatusReason());
        };
    }

    private static DispatchPlanItem toItem(String planId, int seq, DispatchPreviewItem p, LocalDateTime now) {
        DispatchPlanItem i = new DispatchPlanItem();
        i.setPlanId(planId);
        i.setSeq(seq);
        i.setReportId(p.getReportId());
        i.setReportName(p.getReportName());
        i.setCatalogVersion(p.getCatalogVersion());
        i.setRecordId(p.getRecordId());
        i.setDocNo(p.getDocNo());
        i.setCompanyCode(p.getCompanyCode());
        i.setLabel(p.getLabel());
        i.setAmount(p.getAmount());
        i.setBizDate(p.getBizDate());
        i.setRuleId(p.getRuleId());
        i.setRuleName(p.getRuleName());
        i.setRuleVersion(p.getRuleVersion());
        i.setStatus(DispatchPlanItem.PENDING);
        i.setAttemptCount(0);
        i.setUpdatedAt(now);
        return i;
    }
}
