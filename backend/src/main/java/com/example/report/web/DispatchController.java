package com.example.report.web;

import com.example.report.agent.ConversationCards;
import com.example.report.agent.PlanPayload;
import com.example.report.agent.PreviewPayload;
import com.example.report.agent.ReportChoicePayload;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.ApiException;
import com.example.report.common.Result;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.CardStateService;
import com.example.report.dispatch.DispatchResultPayload;
import com.example.report.dispatch.DispatchService;
import com.example.report.dispatch.DispatchTraceService;
import com.example.report.dispatch.PlanService;
import com.example.report.dispatch.PlanSnapshot;
import com.example.report.dispatch.PreviewCommand;
import com.example.report.dispatch.PreviewOutcome;
import com.example.report.dispatch.PreviewJobService;
import com.example.report.dispatch.PreviewService;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.rule.Candidate;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 预览、待确认清单、派单执行的确定性接口。Agent 工具与界面操作走的是同一套服务，
 * 所有接口都在服务端按登录态校验归属（租户 + 用户）、报表权限、公司范围、状态与版本，前端传来的任何范围都不被信任。
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final PermissionService permissionService;
    private final PreviewService previewService;
    private final PlanService planService;
    private final DispatchService dispatchService;
    private final DispatchTraceService traceService;
    private final CardStateService cardStateService;
    private final ConversationService conversationService;
    private final ConversationCards conversationCards;
    private final ReportCatalogService catalogService;
    private final PreviewJobService previewJobs;

    public DispatchController(PermissionService permissionService, PreviewService previewService, PlanService planService,
                              DispatchService dispatchService, DispatchTraceService traceService,
                              CardStateService cardStateService, ConversationService conversationService,
                              ConversationCards conversationCards, ReportCatalogService catalogService,
                              PreviewJobService previewJobs) {
        this.permissionService = permissionService;
        this.previewService = previewService;
        this.planService = planService;
        this.dispatchService = dispatchService;
        this.traceService = traceService;
        this.cardStateService = cardStateService;
        this.conversationService = conversationService;
        this.conversationCards = conversationCards;
        this.catalogService = catalogService;
        this.previewJobs = previewJobs;
    }

    /**
     * 创建预览：报表选择卡片上选定报表后调用（reportIds），也可以按说法查询（reportQuery）。
     * 与 Agent 工具走同一条链路：权限内解析 → 范围 → 版本 → 求值 → 作废同会话旧预览与清单。
     */
    @PostMapping("/previews")
    public Result<PreviewResponse> createPreview(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                 @RequestBody PreviewRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        String conversationId = blankToNull(request.getConversationId());
        if (conversationId != null) {
            conversationService.getOwned(user, conversationId);
        }
        boolean selection = request.getReportIds() != null && !request.getReportIds().isEmpty();
        PreviewCommand command = new PreviewCommand(PreviewCommand.OPERATION_PREVIEW, selection ? "selection" : "api",
                request.getReportQuery(), request.getReportIds(), new PreviewCommand.Filters(request.getCompanyCode()),
                request.getExcludeDocNos(), request.getScopeMode());
        PreviewOutcome outcome = previewService.preview(user, conversationId, command);
        return Result.ok(switch (outcome.status()) {
            case OK -> new PreviewResponse("ok", conversationCards.recordSelectionPreview(user, conversationId, outcome), null, null);
            case AMBIGUOUS -> new PreviewResponse("ambiguous", null, new ReportChoicePayload(request.getReportQuery(),
                    outcome.resolution().candidates(), outcome.resolution().preselected(), request.getCompanyCode(),
                    request.getExcludeDocNos() == null ? List.of() : request.getExcludeDocNos(), command.scopeMode()),
                    "找到多个相关报表，请选择");
            case NOT_FOUND -> new PreviewResponse("not_found", null, null, outcome.resolution().noAccessibleReports()
                    ? "当前账号没有可访问的可派单报表" : "没有找到匹配的报表，请补充名称或业务域");
        });
    }

    @PostMapping("/previews/jobs")
    public Result<PreviewJobService.Job> createPreviewJob(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                           @RequestBody PreviewRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        String conversationId = blankToNull(request.getConversationId());
        if (conversationId != null) conversationService.getOwned(user, conversationId);
        boolean selection = request.getReportIds() != null && !request.getReportIds().isEmpty();
        PreviewCommand command = new PreviewCommand(PreviewCommand.OPERATION_PREVIEW, selection ? "selection" : "api",
                request.getReportQuery(), request.getReportIds(), new PreviewCommand.Filters(request.getCompanyCode()),
                request.getExcludeDocNos(), request.getScopeMode());
        return Result.ok(previewJobs.submit(user, conversationId, command));
    }

    @GetMapping("/previews/jobs/{jobId}")
    public Result<PreviewJobService.Job> previewJob(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                     @PathVariable String jobId) {
        return Result.ok(previewJobs.get(permissionService.resolve(userId), jobId));
    }

    @GetMapping("/previews/jobs")
    public Result<PreviewJobService.Job> recoverPreviewJob(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                         @RequestParam String conversationId) {
        CurrentUser user = permissionService.resolve(userId);
        conversationService.getOwned(user, conversationId);
        return Result.ok(previewJobs.latestForConversation(user, conversationId));
    }

    @PostMapping("/previews/jobs/{jobId}/cancel")
    public Result<PreviewJobService.Job> cancelPreviewJob(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                           @PathVariable String jobId) {
        return Result.ok(previewJobs.cancel(permissionService.resolve(userId), jobId));
    }

    /** 预览汇总、状态与版本（状态读取时做懒惰校验） */
    @GetMapping("/previews/{previewId}")
    public Result<PreviewPayload> preview(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                          @PathVariable String previewId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(PreviewPayload.of(previewService.getOwned(user, previewId), catalogService));
    }

    /** 预览记录分页；服务端限制单页大小，避免大卡片进入浏览器。 */
    @GetMapping("/previews/{previewId}/items")
    public Result<List<Candidate>> previewItems(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                @PathVariable String previewId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(previewService.pageOwned(user, previewId, page, size));
    }

    /** 基于预览生成待确认清单；Idempotency-Key 相同的重复请求返回第一次生成的清单 */
    @PostMapping("/plans")
    public Result<PlanPayload> createPlan(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          @RequestBody PlanRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        if (blankToNull(request.getPreviewId()) == null) {
            throw new ApiException("请指定预览");
        }
        String conversationId = blankToNull(request.getConversationId());
        if (conversationId != null) {
            conversationService.getOwned(user, conversationId);
        }
        PlanSnapshot plan = planService.create(user, conversationId, request.getPreviewId(), request.getExcludeDocNos(), idempotencyKey);
        // 幂等重放返回第一次的清单，卡片已经记过，不再重复写进会话
        return Result.ok(plan.replayed() ? PlanPayload.of(plan)
                : conversationCards.recordPlan(user, plan.plan().getConversationId(), plan));
    }

    @GetMapping("/plans/{planId}")
    public Result<PlanPayload> plan(@RequestHeader(PermissionService.USER_HEADER) String userId, @PathVariable String planId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(PlanPayload.of(planService.getOwned(user, planId)));
    }

    /** 逐条执行结果 */
    @GetMapping("/plans/{planId}/items")
    public Result<List<DispatchPlanItem>> planItems(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                    @PathVariable String planId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(planService.pageOwned(user, planId, page, size));
    }

    /** 确认执行待确认清单（不经过模型，最终门槛在这里）；重复确认返回第一次的结果 */
    @PostMapping("/plans/{planId}/confirm")
    public Result<DispatchResultPayload> confirm(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                 @PathVariable String planId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(dispatchService.confirm(user, planId));
    }

    @PostMapping("/plans/{planId}/retry-failed")
    public Result<DispatchResultPayload> retryFailed(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                     @PathVariable String planId) {
        return Result.ok(dispatchService.retryFailed(permissionService.resolve(userId), planId));
    }

    @PostMapping("/plans/{planId}/reconcile")
    public Result<DispatchResultPayload> reconcile(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                   @PathVariable String planId) {
        return Result.ok(dispatchService.reconcile(permissionService.resolve(userId), planId));
    }

    /** 兼容旧前端的执行接口，等同于 confirm */
    @PostMapping("/plans/{planId}/execute")
    public Result<DispatchResultPayload> execute(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                 @PathVariable String planId) {
        return confirm(userId, planId);
    }

    @PostMapping("/plans/{planId}/cancel")
    public Result<CardStateService.CardState> cancel(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                     @PathVariable String planId) {
        CurrentUser user = permissionService.resolve(userId);
        DispatchPlan plan = dispatchService.cancel(user, planId);
        return Result.ok(cardStateService.planStates(user, List.of(plan.getId())).get(plan.getId()));
    }

    /** 链路追溯：用户原话 → 工具调用 → 预览 → 清单 → 逐条结果 → 审计 → 规则版本（本人或同租户管理员） */
    @GetMapping("/plans/{planId}/trace")
    public Result<Map<String, Object>> trace(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                             @PathVariable String planId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(traceService.trace(user, planId));
    }

    /** 报表页手工派单：reportId 或旧 reportType 均可，按记录主键 */
    @PostMapping("/direct")
    public Result<DispatchResultPayload> direct(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                @RequestBody DirectRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        if (request.getIds() == null || request.getIds().isEmpty()) {
            throw new ApiException("请先勾选需要派单的记录");
        }
        String report = blankToNull(request.getReportId()) != null ? request.getReportId() : request.getReportType();
        return Result.ok(dispatchService.dispatchDirect(user, report, request.getIds()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record PreviewResponse(String status, PreviewPayload preview, ReportChoicePayload choice, String message) {
    }

    @Data
    public static class PreviewRequest {
        private String conversationId;
        /** 选择卡片上选定的报表（服务端按权限校验） */
        private List<String> reportIds;
        /** 或者按说法查询 */
        private String reportQuery;
        private String companyCode;
        private List<String> excludeDocNos;
        private String scopeMode;
    }

    @Data
    public static class PlanRequest {
        private String previewId;
        private String conversationId;
        private List<String> excludeDocNos;
    }

    @Data
    public static class DirectRequest {
        private String reportId;
        /** 旧报表页仍传 sales / receivable / expense，经映射表转换 */
        private String reportType;
        private List<String> ids;
    }
}
