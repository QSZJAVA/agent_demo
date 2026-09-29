package com.example.report.agent;

import com.example.report.catalog.MatchType;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.catalog.ReportRef;
import com.example.report.catalog.ResolveResult;
import com.example.report.common.ApiException;
import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.DispatchResultPayload;
import com.example.report.dispatch.DispatchService;
import com.example.report.dispatch.PlanService;
import com.example.report.dispatch.PlanSnapshot;
import com.example.report.dispatch.PreviewCommand;
import com.example.report.dispatch.PreviewOutcome;
import com.example.report.dispatch.PreviewJobService;
import com.example.report.dispatch.PreviewService;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.rule.Candidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 的两个工具。用户身份从 ToolContext 取；报表由服务端在用户有权限的目录内解析，公司范围由服务端校验。
 * 模型只能给出“用户对报表的说法”和白名单筛选条件，给不出表名、报表 ID 或 SQL；预览生成快照，执行只对快照内的记录生效。
 */
@Slf4j
@Component
public class DispatchTools {

    public static final String TOOL_PREVIEW = "previewDispatchable";
    public static final String TOOL_DISPATCH = "dispatch";
    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_FALLBACK = "fallback";
    private static final int MAX_RECORDS_PER_REPORT_FOR_MODEL = 50;

    private final PermissionService permissionService;
    private final PreviewService previewService;
    private final PlanService planService;
    private final DispatchService dispatchService;
    private final ReportCatalogService catalogService;
    private final ConversationService conversationService;
    private final AgentProperties props;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PreviewJobService previewJobs;

    public DispatchTools(PermissionService permissionService, PreviewService previewService, PlanService planService,
                         DispatchService dispatchService, ReportCatalogService catalogService,
                         ConversationService conversationService, AgentProperties props) {
        this.permissionService = permissionService;
        this.previewService = previewService;
        this.planService = planService;
        this.dispatchService = dispatchService;
        this.catalogService = catalogService;
        this.conversationService = conversationService;
        this.props = props;
    }

    @Tool(name = TOOL_PREVIEW, description = """
            查询当前用户有权限的报表中、按各报表当前生效的派单规则应当派单的记录，生成预览快照并以卡片展示给用户。
            用户问"有哪些可以派单 / 待派单 / 需要派单 / 帮我看看派单"时调用；不要自己判断哪些记录该派单。
            reportQuery 传用户对报表的原话（正式名称、简称、别名都可以，多张报表可以一起说），由系统在用户有权限的报表目录内解析；
            不要自己把说法翻译成编码，也不要编造报表名称。用户没有指定报表时不传，表示用户有权限的全部可派单报表。
            scopeMode 说明 reportQuery 的含义：replace（默认）= 就查这些报表；append = 在上一轮预览范围上追加这些报表；remove = 从上一轮预览范围中去掉这些报表。
            excludeDocNos 用于用户想在预览里排除某些记录时（例如"把云服务删掉"），可传单据号，也可传用户描述的摘要关键词。
            返回 status：ok 已生成预览；ambiguous 命中多张报表，界面已展示选择卡片，请用户在卡片上选择，不要自行猜测；
            not_found 没有匹配的报表，请用户补充完整的报表名称或业务域；error 失败，按 message 向用户说明。""")
    public Object previewDispatchable(
            @ToolParam(required = false, description = "用户对报表的原话，例如 销售台账 / 应收和费用 / 客户对账；不填表示用户有权限的全部可派单报表")
            String reportQuery,
            @ToolParam(required = false, description = "用户明确指定的公司代码，例如 B；不填表示当前用户默认可见公司范围")
            String companyCode,
            @ToolParam(required = false, description = "本次预览要排除的记录：可传单据号（如 SO2026007），也可传用户说出的摘要关键词（如 云服务），工具会先按单据号精确匹配，未命中再按摘要模糊匹配")
            List<String> excludeDocNos,
            @ToolParam(required = false, description = "reportQuery 的含义：replace（默认，就查这些报表）/ append（在上一轮预览范围上追加这些报表）/ remove（从上一轮预览范围中去掉这些报表）")
            String scopeMode,
            ToolContext ctx) {
        AgentEventChannel channel = ToolContextKeys.channel(ctx);
        if (channel != null) {
            channel.markToolCalled(TOOL_PREVIEW);
        }
        return preview(reportQuery, companyCode, excludeDocNos, scopeMode, SOURCE_AGENT, ctx);
    }

    /** 服务端已识别范围的查询：用于明确公司查询，以及模型未调用工具时的补查。 */
    public Object fallbackPreview(String reportQuery, String companyCode, ToolContext ctx) {
        return preview(reportQuery, companyCode, null, null, SOURCE_FALLBACK, ctx);
    }

    private Object preview(String reportQuery, String companyCode, List<String> excludeDocNos, String scopeMode,
                           String source, ToolContext ctx) {
        String userId = ToolContextKeys.userId(ctx);
        String conversationId = ToolContextKeys.conversationId(ctx);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("reportQuery", reportQuery == null ? "" : reportQuery);
        args.put("companyCode", companyCode == null ? "" : companyCode);
        args.put("excludeDocNos", excludeDocNos == null ? List.of() : excludeDocNos);
        args.put("scopeMode", scopeMode == null ? "" : scopeMode);
        args.put("source", source);
        conversationService.logToolCall(conversationId, userId, TOOL_PREVIEW, args);
        try {
            CurrentUser user = permissionService.resolve(userId);
            PreviewService.resolveCompanies(user, companyCode);
            PreviewCommand command = new PreviewCommand(PreviewCommand.OPERATION_PREVIEW, source, reportQuery, null,
                    new PreviewCommand.Filters(companyCode), excludeDocNos, resolveScopeMode(scopeMode, ctx));
            AgentEventChannel channel = ToolContextKeys.channel(ctx);
            ResolveResult initial = catalogService.resolve(user, reportQuery);
            if (previewJobs != null && channel != null && initial.matchType() != MatchType.NONE
                    && initial.matchType() != MatchType.AMBIGUOUS) {
                PreviewJobService.Job job = previewJobs.submit(user, conversationId, command);
                if ("FAILED".equals(job.status())) return error(job.message());
                if (channel != null) channel.emit(AgentEvent.PREVIEW_JOB, Map.of("jobId", job.id()));
                conversationService.logToolResult(conversationId, userId, TOOL_PREVIEW,
                        "previewJobId=" + job.id(), null, null);
                return Map.of("status", "querying", "message", "预览查询已开始，界面正在展示进度。查询完成后会显示预览卡片，请不要编造条数或派单结果。");
            }
            PreviewOutcome outcome = previewService.preview(user, conversationId, command);
            return switch (outcome.status()) {
                case NOT_FOUND -> {
                    String message = outcome.resolution().noAccessibleReports()
                            ? "当前账号没有可访问的可派单报表"
                            : "没有找到匹配的报表，请补充完整的报表名称或业务域";
                    conversationService.logToolResult(conversationId, userId, TOOL_PREVIEW, "not_found", null, null);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "not_found");
                    result.put("message", message + "。不要编造报表或结论，也不要改查其他报表。");
                    yield result;
                }
                case AMBIGUOUS -> {
                    ResolveResult r = outcome.resolution();
                    ReportChoicePayload payload = new ReportChoicePayload(reportQuery, r.candidates(), r.preselected(),
                            companyCode, excludeDocNos == null ? List.of() : excludeDocNos, command.scopeMode());
                    if (channel != null) {
                        channel.emit(AgentEvent.CHOICE, payload);
                    }
                    conversationService.logCard(conversationId, userId, "choice", payload, null, null);
                    conversationService.logToolResult(conversationId, userId, TOOL_PREVIEW,
                            "ambiguous candidates=" + r.candidates().size(), null, null);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "ambiguous");
                    result.put("candidates", r.candidates().stream().map(ReportRef::reportName).toList());
                    result.put("message", "找到多个相关报表，界面已展示报表选择卡片。请提示用户在卡片上选择要查询的报表，不要自行挑选或猜测。");
                    yield result;
                }
                case OK -> {
                    PreviewPayload payload = PreviewPayload.of(outcome.snapshot(), catalogService);
                    if (channel != null) {
                        channel.emit(AgentEvent.PREVIEW, payload);
                    }
                    conversationService.logCard(conversationId, userId, "preview", payload, payload.previewId(), null);
                    conversationService.logToolResult(conversationId, userId, TOOL_PREVIEW,
                            "previewId=" + payload.previewId() + " total=" + payload.total(), payload.previewId(), null);
                    yield modelSummary(payload, outcome.resolution());
                }
            };
        } catch (ApiException e) {
            conversationService.logToolResult(conversationId, userId, TOOL_PREVIEW, "error: " + e.getMessage(), null, null);
            return error(e.getMessage());
        }
    }

    /**
     * reportQuery 的含义。模型显式传了 scopeMode 就以它为准：它和 reportQuery 是模型同一次填写的，含义一定一致；
     * 没传时才退回服务端识别出的追加 / 排除语义。只靠服务端识别会出错：用户换个说法没识别出"排除"，
     * 模型按提示只传了要排除的报表，结果就成了"只查这张报表"，语义刚好相反。
     */
    private static String resolveScopeMode(String scopeMode, ToolContext ctx) {
        if (scopeMode != null && !scopeMode.isBlank()) {
            return scopeMode;
        }
        if (ToolContextKeys.previewAppend(ctx)) {
            return PreviewService.SCOPE_APPEND;
        }
        return ToolContextKeys.previewRemove(ctx) ? PreviewService.SCOPE_REMOVE : PreviewService.SCOPE_REPLACE;
    }

    @Tool(name = TOOL_DISPATCH, description = """
            对最近一次预览快照中的记录发起派单，可以排除部分单据号。只对预览快照内的记录生效。
            用户说"派单 / 剩下的都派 / 除了 X 其他都派 / 全部派单"时调用。
            excludeDocNos 只能来自用户明确说出的单据号或预览结果中的单据号，不要编造。
            返回 status：pending_confirm 表示已生成待确认清单，需要用户在界面上点击确认；done 表示已执行；error 表示失败，按 message 向用户说明。""")
    public Object dispatch(
            @ToolParam(required = false, description = "预览编号 previewId；不传则使用本会话最近一次预览")
            String previewId,
            @ToolParam(required = false, description = "不派单的单据号列表，例如 [\"SO2026002\"]")
            List<String> excludeDocNos,
            ToolContext ctx) {
        String userId = ToolContextKeys.userId(ctx);
        String conversationId = ToolContextKeys.conversationId(ctx);
        AgentEventChannel channel = ToolContextKeys.channel(ctx);
        if (channel != null) {
            channel.markToolCalled(TOOL_DISPATCH);
        }
        if (channel != null && channel.hasEmitted(AgentEvent.PREVIEW_JOB)) {
            return error("新的预览仍在查询中，请等待预览卡片出现后再生成派单清单");
        }
        // 本轮重新预览后，客户端带来的勾选项属于旧卡片，不能污染新范围
        List<String> uiExcludes = channel != null && channel.hasEmitted(AgentEvent.PREVIEW)
                ? List.of() : ToolContextKeys.uiExcludes(ctx);
        List<com.example.report.dispatch.RecordKey> recordExcludes = channel != null && channel.hasEmitted(AgentEvent.PREVIEW)
                ? List.of() : ToolContextKeys.uiExcludedRecords(ctx);
        conversationService.logToolCall(conversationId, userId, TOOL_DISPATCH,
                Map.of("previewId", previewId == null ? "" : previewId,
                        "excludeDocNos", excludeDocNos == null ? List.of() : excludeDocNos,
                        "uiExcludes", uiExcludes, "uiExcludedRecords", recordExcludes));
        try {
            CurrentUser user = permissionService.resolve(userId);
            String targetPreviewId = previewId == null || previewId.isBlank() ? null : previewId.trim();
            // 即使排除集合为空（全选），客户端提供的来源也不能被模型省略或改成另一份预览。
            // 自然语言明确在本轮重新预览时，旧勾选不参与新范围；卡片按钮直接走 REST 建单。
            String uiPreviewId = channel != null && channel.hasEmitted(AgentEvent.PREVIEW)
                    ? null : ToolContextKeys.uiPreviewId(ctx);
            if (uiPreviewId != null) {
                if (targetPreviewId != null && !uiPreviewId.equals(targetPreviewId)) {
                    throw new ApiException("勾选所属预览已变化，请刷新并重新选择");
                }
                targetPreviewId = uiPreviewId;
            } else if (!recordExcludes.isEmpty()) {
                throw new ApiException("请指定勾选所属预览，刷新后重新选择");
            }
            // 合并模型给的排除项与前端取消勾选的排除项；单据号不区分大小写
            Set<String> excludes = new LinkedHashSet<>();
            if (excludeDocNos != null) {
                excludeDocNos.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(excludes::add);
            }
            uiExcludes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(excludes::add);

            PlanSnapshot plan = planService.create(user, conversationId, targetPreviewId, new ArrayList<>(excludes), null, recordExcludes);
            List<String> excluded = plan.excluded();
            if (props.getDispatch().isRequireConfirm()) {
                PlanPayload payload = PlanPayload.of(plan);
                if (channel != null) {
                    channel.emit(AgentEvent.PLAN, payload);
                }
                conversationService.logCard(conversationId, userId, "plan", payload, plan.plan().getPreviewId(), plan.plan().getId());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "pending_confirm");
                result.put("planId", plan.plan().getId());
                result.put("count", plan.items().size());
                result.put("excluded", excluded);
                result.put("message", "已生成待确认的派单清单，共 " + plan.items().size() + " 条。请提示用户在界面的确认卡片上点击\"确认派单\"后才会真正执行。");
                conversationService.logToolResult(conversationId, userId, TOOL_DISPATCH,
                        "pending_confirm planId=" + plan.plan().getId() + " count=" + plan.items().size(),
                        plan.plan().getPreviewId(), plan.plan().getId());
                return result;
            }

            DispatchResultPayload executed = dispatchService.confirm(user, plan.plan().getId(), ToolContextKeys.traceId(ctx));
            if (channel != null) {
                channel.emit(AgentEvent.RESULT, executed);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "done");
            result.put("planId", plan.plan().getId());
            result.put("successCount", executed.successCount());
            result.put("failedCount", executed.failedCount());
            result.put("successDocNos", executed.success().stream().map(Candidate::docNo).toList());
            result.put("failed", executed.failed());
            result.put("excluded", excluded);
            conversationService.logToolResult(conversationId, userId, TOOL_DISPATCH,
                    "done success=" + executed.successCount() + " failed=" + executed.failedCount(),
                    plan.plan().getPreviewId(), plan.plan().getId());
            return result;
        } catch (ApiException e) {
            conversationService.logToolResult(conversationId, userId, TOOL_DISPATCH, "error: " + e.getMessage(), null, null);
            return error(e.getMessage());
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }

    /** 给模型的精简摘要：每张报表最多 50 条，全量数据不经过模型，也不含报表 ID、预览编号以外的内部标识 */
    private static Map<String, Object> modelSummary(PreviewPayload payload, ResolveResult resolution) {
        List<Map<String, Object>> byReport = new ArrayList<>();
        for (PreviewPayload.ReportCount count : payload.byReport()) {
            List<Candidate> list = payload.records().stream().filter(c -> c.reportId().equals(count.reportId())).toList();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("reportName", count.reportName());
            r.put("count", count.count());
            r.put("ruleDescription", payload.ruleDescriptions().get(count.reportId()));
            List<Map<String, Object>> records = new ArrayList<>();
            for (Candidate c : list.stream().limit(MAX_RECORDS_PER_REPORT_FOR_MODEL).toList()) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("docNo", c.docNo());
                rec.put("label", c.label());
                rec.put("companyCode", c.companyCode());
                rec.put("amount", c.amount());
                records.add(rec);
            }
            r.put("records", records);
            if (list.size() > MAX_RECORDS_PER_REPORT_FOR_MODEL) {
                r.put("note", "仅列出前 " + MAX_RECORDS_PER_REPORT_FOR_MODEL + " 条，完整清单已在界面表格中展示");
            }
            byReport.add(r);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ok");
        summary.put("previewId", payload.previewId());
        summary.put("total", payload.total());
        summary.put("reports", payload.byReport().stream().map(PreviewPayload.ReportCount::reportName).toList());
        summary.put("byReport", byReport);
        List<String> notes = new ArrayList<>();
        notes.add("完整清单已以表格形式展示给用户，回复时按报表汇总条数并简述规则，不要逐条复述全部记录。");
        if (resolution.matchType() == MatchType.FUZZY) {
            String names = String.join("、", payload.byReport().stream().map(PreviewPayload.ReportCount::reportName).toList());
            notes.add("用户说的“" + resolution.query() + "”没有精确对应的报表，已按最接近的“" + names
                    + "”查询，回复时请向用户说明并请其确认。");
        }
        if (!resolution.unrecognized().isEmpty()) {
            notes.add("以下说法在用户可访问的报表中没有对应：" + String.join("、", resolution.unrecognized())
                    + "，回复时请提示用户补充完整名称，不要替用户猜测。");
        }
        summary.put("note", String.join(" ", notes));
        return summary;
    }
}
