package com.example.report.investigation;

import com.example.report.common.ApiException;
import com.example.report.dispatch.PreviewService;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.dispatch.store.PreviewRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.permission.CurrentUser;
import com.example.report.security.IdentityStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 调查读取权限边界；每次工具、报告和证据读取重取当前账号授权，并限制到指定清单全部范围。 */
@Service
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationAccessPolicy {
    private final IdentityStore identities;
    private final PlanRepository plans;
    private final PreviewRepository previews;
    private final PreviewService previewService;
    public InvestigationAccessPolicy(IdentityStore identities, PlanRepository plans, PreviewRepository previews, PreviewService previewService) {
        this.identities=identities; this.plans=plans; this.previews=previews; this.previewService=previewService;
    }
    /** 解析当前启用身份；不能持续使用任务创建时的admin和权限副本。 */
    public CurrentUser current(String tenant, String actor) { return identities.resolve(tenant, actor); }
    /** 返回可读清单；同租户管理员仍须拥有全部公司、报表和会话范围权限。 */
    public DispatchPlan require(CurrentUser actor, String planId) {
        var user=current(actor.tenantId(),actor.userId());
        var plan=plans.find(planId).filter(p -> user.tenantId().equals(p.getTenantId())
                && (user.userId().equals(p.getUserId()) || user.admin())).orElseThrow(() -> ApiException.notFound("清单不存在或无权调查"));
        var preview=previews.find(plan.getPreviewId()).orElseThrow(() -> ApiException.notFound("清单来源不存在"));
        previewService.requireReadable(user,preview);
        if (plan.getConversationId()!=null) previewService.requireConversationReadable(user,plan.getConversationId());
        return plan;
    }
}
