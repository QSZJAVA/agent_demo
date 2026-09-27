package com.example.report.dispatch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.dispatch.store.PreviewRepository;
import com.example.report.entity.DispatchAudit;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchAuditMapper;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 派单链路追溯（P0-09）：从一份清单出发，串起 用户原话 → 工具调用 → 预览（请求协议、范围、版本）→ 清单（排除项、确认人）
 * → 逐条结果 → 审计记录 → 命中的规则版本。本人或同租户管理员可查。
 */
@Service
public class DispatchTraceService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PreviewService previewService;

    private final PlanRepository plans;
    private final PreviewRepository previews;
    private final DispatchAuditMapper auditMapper;
    private final DispatchRuleMapper ruleMapper;
    private final ConversationService conversationService;

    public DispatchTraceService(PlanRepository plans, PreviewRepository previews, DispatchAuditMapper auditMapper,
                                DispatchRuleMapper ruleMapper, ConversationService conversationService) {
        this.plans = plans;
        this.previews = previews;
        this.auditMapper = auditMapper;
        this.ruleMapper = ruleMapper;
        this.conversationService = conversationService;
    }

    public Map<String, Object> trace(CurrentUser user, String planId) {
        DispatchPlan plan = plans.find(planId)
                .filter(p -> Objects.equals(p.getTenantId(), user.tenantId())
                        && (Objects.equals(p.getUserId(), user.userId()) || user.admin()))
                .orElseThrow(() -> ApiException.notFound("待确认清单不存在"));
        DispatchPreview preview = previews.find(plan.getPreviewId()).orElse(null);
        if (preview != null && previewService != null) previewService.requireReadable(user, preview);
        List<DispatchPlanItem> items = plans.items(plan.getId());
        List<DispatchAudit> audits = auditMapper.selectList(new LambdaQueryWrapper<DispatchAudit>()
                .eq(DispatchAudit::getTenantId, user.tenantId())
                .eq(DispatchAudit::getPlanId, plan.getId())
                .orderByAsc(DispatchAudit::getId));
        Set<Long> ruleIds = items.stream().map(DispatchPlanItem::getRuleId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<DispatchRule> rules = ruleIds.isEmpty() ? List.of() : ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                .eq(DispatchRule::getTenantId, user.tenantId()).in(DispatchRule::getId, ruleIds));

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("plan", planView(plan));
        trace.put("preview", preview == null ? null : previewView(preview));
        trace.put("items", items);
        trace.put("audits", audits);
        trace.put("rules", rules);
        if (plan.getConversationId() != null && preview != null) {
            LocalDateTime until = plan.getFinishedAt() != null ? plan.getFinishedAt() : LocalDateTime.now();
            trace.put("messages", conversationService.traceMessages(plan.getConversationId(), preview.getCreatedAt(), until));
        } else {
            trace.put("messages", List.of());
        }
        return trace;
    }

    private static Map<String, Object> planView(DispatchPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("previewId", p.getPreviewId());
        m.put("tenantId", p.getTenantId());
        m.put("userId", p.getUserId());
        m.put("conversationId", p.getConversationId());
        m.put("status", p.getStatus());
        m.put("statusReason", p.getStatusReason());
        m.put("excluded", new PlanSnapshot(p, List.of()).excluded());
        m.put("itemCount", p.getItemCount());
        m.put("successCount", p.getSuccessCount());
        m.put("failedCount", p.getFailedCount());
        m.put("idempotencyKey", p.getIdempotencyKey());
        m.put("createdAt", p.getCreatedAt());
        m.put("expiresAt", p.getExpiresAt());
        m.put("confirmedAt", p.getConfirmedAt());
        m.put("confirmedBy", p.getConfirmedBy());
        m.put("finishedAt", p.getFinishedAt());
        return m;
    }

    private static Map<String, Object> previewView(DispatchPreview p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("source", p.getSource());
        m.put("status", p.getStatus());
        m.put("statusReason", p.getStatusReason());
        m.put("query", JsonUtil.toMap(p.getQueryJson()));
        m.put("reportIds", DispatchVersionService.reportIds(p));
        m.put("companyCodes", DispatchVersionService.companies(p));
        m.put("catalogVersion", p.getCatalogVersion());
        m.put("ruleVersion", p.getRuleVersion());
        m.put("permissionVersion", p.getPermissionVersion());
        m.put("totalCount", p.getTotalCount());
        m.put("totalAmount", p.getTotalAmount());
        m.put("createdAt", p.getCreatedAt());
        m.put("expiresAt", p.getExpiresAt());
        return m;
    }
}
