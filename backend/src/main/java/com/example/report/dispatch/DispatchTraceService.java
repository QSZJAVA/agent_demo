package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.dispatch.store.PreviewRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.entity.DispatchPreview;
import com.example.report.trace.TraceReader;
import com.example.report.config.ResourceQuotaService;
import com.example.report.permission.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 派单链路追溯（P0-09）：从一份清单出发，串起 用户原话 → 工具调用 → 预览（请求协议、范围、版本）→ 清单（排除项、确认人）
 * → 逐条结果 → 审计记录 → 命中的规则版本。本人或同租户管理员可查。
 */
@Service
public class DispatchTraceService {

    private final PreviewService previewService;

    private final PlanRepository plans;
    private final PreviewRepository previews;
    private final TraceReader reader;
    private final ResourceQuotaService quotas;

    public DispatchTraceService(PlanRepository plans, PreviewRepository previews, TraceReader reader, ResourceQuotaService quotas,
                                PreviewService previewService) {
        this.plans = plans;
        this.previews = previews;
        this.reader = reader;
        this.quotas = quotas;
        this.previewService = previewService;
    }

    public Map<String, Object> trace(CurrentUser user, String planId) {
        DispatchPlan plan = readable(user, planId);
        DispatchPreview preview = previews.find(plan.getPreviewId()).orElseThrow();
        List<DispatchPlanItem> items = plans.items(plan.getId());
        // 只展示清单创建时冻结的规则；缺失即报告缺失，不拿现行规则填补历史。
        List<Map<String, Object>> rules = items.stream().map(DispatchPlanItem::getRuleSnapshot)
                .filter(Objects::nonNull).distinct().map(JsonUtil::toMap).toList();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("plan", planView(plan));
        trace.put("preview", previewView(preview));
        trace.put("rules", rules);
        trace.put("integrity", reader.integrity(plan));
        trace.put("items", reader.page(plan, "items", 0, 50));
        trace.put("audits", reader.page(plan, "audits", 0, 50));
        trace.put("messages", reader.page(plan, "messages", 0, 50));
        trace.put("events", reader.page(plan, "events", 0, 50));
        return trace;
    }

    public TraceReader.Page page(CurrentUser user, String planId, String section, long afterId, int size) {
        return reader.page(readable(user, planId), section, afterId, size);
    }

    public Map<String, Object> retry(CurrentUser user, String planId) {
        DispatchPlan plan = readable(user, planId);
        DispatchPreview preview = previews.find(plan.getPreviewId()).orElseThrow();
        try (var permit = quotas.acquire(user, "trace-retry", DispatchVersionService.reportIds(preview))) {
            ResourceQuotaService.check(permit);
            return Map.of("scheduledCount", reader.retry(plan));
        }
    }

    private DispatchPlan readable(CurrentUser user, String planId) {
        DispatchPlan plan = plans.find(planId)
                .filter(p -> Objects.equals(p.getTenantId(), user.tenantId())
                        && (Objects.equals(p.getUserId(), user.userId()) || user.admin()))
                .orElseThrow(() -> ApiException.notFound("待确认清单不存在"));
        DispatchPreview preview = previews.find(plan.getPreviewId()).orElse(null);
        if (preview == null) throw ApiException.notFound("追溯来源预览缺失，无法校验数据范围");
        previewService.requireReadable(user, preview);
        // 追溯包含整段会话的自由文本，不能仅凭当前清单的预览权限授权其他报表的消息。
        if (plan.getConversationId() != null) {
            previewService.requireConversationReadable(user, plan.getConversationId());
        }
        return plan;
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
        m.put("executionVersion", p.getExecutionVersion());
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
