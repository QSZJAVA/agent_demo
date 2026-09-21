package com.example.report.agent;

import com.example.report.common.ApiException;
import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.DispatchPlan;
import com.example.report.dispatch.DispatchResultPayload;
import com.example.report.dispatch.DispatchService;
import com.example.report.dispatch.PlanStore;
import com.example.report.dispatch.PreviewStore;
import com.example.report.dispatch.Snapshot;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.report.ReportType;
import com.example.report.rule.Candidate;
import com.example.report.rule.DispatchCandidateService;
import com.example.report.rule.RuleCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 的两个工具。用户身份从 ToolContext 取；报表和公司范围都由服务端再次校验。
 * 预览生成快照，执行只对快照内的记录生效；模型永远不需要自己罗列记录 ID。
 */
@Slf4j
@Component
public class DispatchTools {

    public static final String TOOL_PREVIEW = "previewDispatchable";
    public static final String TOOL_DISPATCH = "dispatch";
    private static final int MAX_RECORDS_PER_REPORT_FOR_MODEL = 50;

    private final PermissionService permissionService;
    private final DispatchCandidateService candidateService;
    private final RuleCache ruleCache;
    private final PreviewStore previewStore;
    private final PlanStore planStore;
    private final DispatchService dispatchService;
    private final ConversationService conversationService;
    private final AgentProperties props;

    public DispatchTools(PermissionService permissionService, DispatchCandidateService candidateService, RuleCache ruleCache,
                         PreviewStore previewStore, PlanStore planStore, DispatchService dispatchService,
                         ConversationService conversationService, AgentProperties props) {
        this.permissionService = permissionService;
        this.candidateService = candidateService;
        this.ruleCache = ruleCache;
        this.previewStore = previewStore;
        this.planStore = planStore;
        this.dispatchService = dispatchService;
        this.conversationService = conversationService;
        this.props = props;
    }

    @Tool(name = TOOL_PREVIEW, description = """
            查询当前用户可见范围内、按各报表当前生效的派单规则应当派单的记录，生成预览快照。
            返回预览编号、总条数、各报表的条数、规则说明和记录清单（单据号、摘要、公司、金额）。
            用户问"有哪些可以派单 / 待派单 / 需要派单 / 帮我看看派单"时调用；不要自己判断哪些记录该派单。
            reportType 允许用英文逗号同时指定多张报表，例如 receivable,expense。
            excludeDocNos 用于用户想在预览里排除某些记录时（例如"把云服务删掉"），可传单据号，也可传用户描述的摘要关键词。""")
    public Object previewDispatchable(
            @ToolParam(required = false, description = "报表类型：sales（销售）/ receivable（应收）/ expense（费用）；多张报表用英文逗号连接，例如 receivable,expense；不填或填 all 表示全部报表")
            String reportType,
            @ToolParam(required = false, description = "用户明确指定的公司代码，例如 B；不填表示当前用户默认可见公司范围")
            String companyCode,
            @ToolParam(required = false, description = "本次预览要排除的记录：可传单据号（如 SO2026007），也可传用户说出的摘要关键词（如 云服务），工具会先按单据号精确匹配，未命中再按摘要模糊匹配")
            List<String> excludeDocNos,
            ToolContext ctx) {
        String userId = ToolContextKeys.userId(ctx);
        String conversationId = ToolContextKeys.conversationId(ctx);
        conversationService.logToolCall(conversationId, userId, TOOL_PREVIEW, Map.of(
                "reportType", reportType == null ? "" : reportType,
                "companyCode", companyCode == null ? "" : companyCode,
                "excludeDocNos", excludeDocNos == null ? List.of() : excludeDocNos));
        try {
            CurrentUser user = permissionService.resolve(userId);
            Set<ReportType> filter = parseReportTypes(reportType);
            if (ToolContextKeys.previewAppend(ctx) && !filter.isEmpty()) {
                // 用户是在当前范围上追加报表：与上一轮预览的范围合并，避免只查到新增的那张报表
                filter = appendToPrevious(userId, conversationId, filter);
            } else if (ToolContextKeys.previewRemove(ctx) && !filter.isEmpty()) {
                // 用户是在当前范围上排除报表：从上一轮预览的范围中减去
                filter = removeFromPrevious(userId, conversationId, filter);
            }
            Set<String> companies = resolveCompanies(user, companyCode);
            List<Candidate> candidates = applyExcludes(findCandidates(companies, filter), excludeDocNos);
            List<String> scope = filter.stream().map(ReportType::code).toList();
            Snapshot snapshot = previewStore.save(userId, conversationId, candidates, ruleCache.fingerprint(), scope);
            planStore.supersedeLatest(userId, conversationId);

            PreviewPayload payload = buildPayload(snapshot);
            AgentEventChannel channel = ToolContextKeys.channel(ctx);
            if (channel != null) {
                channel.emit(AgentEvent.PREVIEW, payload);
            }
            conversationService.logCard(conversationId, userId, "preview", payload, snapshot.id(), null);

            Map<String, Object> summary = buildModelSummary(snapshot, filter, scope);
            conversationService.logToolResult(conversationId, userId, TOOL_PREVIEW,
                    "previewId=" + snapshot.id() + " total=" + candidates.size(), snapshot.id(), null);
            return summary;
        } catch (ApiException e) {
            conversationService.logToolResult(conversationId, userId, TOOL_PREVIEW, "error: " + e.getMessage(), null, null);
            return error(e.getMessage());
        }
    }

    /** 保持 Java 调用方兼容；模型工具调用使用带公司/排除参数的重载。 */
    public Object previewDispatchable(String reportType, ToolContext ctx) {
        return previewDispatchable(reportType, null, null, ctx);
    }

    /** 解析报表类型参数：空 / all 表示全部报表；多张报表用逗号等分隔；未知类型仍然报错 */
    private static Set<ReportType> parseReportTypes(String reportType) {
        Set<ReportType> types = EnumSet.noneOf(ReportType.class);
        if (reportType == null || reportType.isBlank()) {
            return types;
        }
        for (String part : reportType.split("[,，、+/\\s]+")) {
            String code = part.trim();
            if (code.isEmpty()) {
                continue;
            }
            if ("all".equalsIgnoreCase(code)) {
                return EnumSet.noneOf(ReportType.class);
            }
            types.add(ReportType.fromCode(code));
        }
        return types;
    }

    /** 追加范围：与上一轮预览的范围合并；上一轮是全部报表时合并后仍是全部报表 */
    private Set<ReportType> appendToPrevious(String userId, String conversationId, Set<ReportType> requested) {
        if (conversationId == null) {
            return requested;
        }
        Optional<Snapshot> latest = previewStore.loadLatest(userId, conversationId);
        if (latest.isEmpty() || latest.get().reportTypes() == null) {
            // 没有可参考的上一轮范围（快照过期或升级前的旧快照），只按本次请求的类型查询
            return requested;
        }
        List<String> previous = latest.get().reportTypes();
        if (previous.isEmpty()) {
            return EnumSet.noneOf(ReportType.class);
        }
        Set<ReportType> merged = EnumSet.copyOf(requested);
        previous.stream().map(ReportType::fromCodeOrNull).filter(Objects::nonNull).forEach(merged::add);
        return merged;
    }

    /**
     * 排除范围：从上一轮预览的范围中减去本次指定的报表。
     * 没有任何可参考的上一轮范围（新会话、快照过期或升级前的旧快照）时，按"全部报表减去指定"处理；
     * 绝不能返回 requested，否则"不要 X 报表"会被理解成"只查 X 报表"，语义刚好相反。
     */
    private Set<ReportType> removeFromPrevious(String userId, String conversationId, Set<ReportType> requested) {
        Set<ReportType> remaining = EnumSet.allOf(ReportType.class);
        if (conversationId != null) {
            Optional<Snapshot> latest = previewStore.loadLatest(userId, conversationId);
            if (latest.isPresent() && latest.get().reportTypes() != null && !latest.get().reportTypes().isEmpty()) {
                remaining = EnumSet.noneOf(ReportType.class);
                latest.get().reportTypes().stream()
                        .map(ReportType::fromCodeOrNull).filter(Objects::nonNull).forEach(remaining::add);
            }
        }
        remaining.removeAll(requested);
        if (remaining.isEmpty()) {
            throw new ApiException("排除之后没有可查询的报表，请确认要保留的报表范围");
        }
        return remaining;
    }

    /** 预览阶段的排除项：优先按单据号精确匹配，未命中再按摘要关键词模糊匹配（用户常只说"云服务"这类描述） */
    private static List<Candidate> applyExcludes(List<Candidate> candidates, List<String> excludeDocNos) {
        if (candidates == null || candidates.isEmpty() || excludeDocNos == null || excludeDocNos.isEmpty()) {
            return candidates;
        }
        List<String> keys = excludeDocNos.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (keys.isEmpty()) {
            return candidates;
        }
        Set<String> docNos = new LinkedHashSet<>();
        keys.forEach(k -> docNos.add(k.toUpperCase(Locale.ROOT)));
        return candidates.stream().filter(c -> {
            if (docNos.contains(c.docNo().toUpperCase(Locale.ROOT))) {
                return false;
            }
            String label = c.label() == null ? "" : c.label();
            return keys.stream().noneMatch(label::contains);
        }).toList();
    }

    /** 空 filter 表示全部报表；指定报表时按枚举顺序逐张查询，保证记录顺序与"全部报表"一致 */
    private List<Candidate> findCandidates(Set<String> companies, Set<ReportType> filter) {
        if (filter.isEmpty()) {
            return candidateService.findCandidates(companies, null);
        }
        List<Candidate> result = new ArrayList<>();
        for (ReportType type : ReportType.values()) {
            if (filter.contains(type)) {
                result.addAll(candidateService.findCandidates(companies, type));
            }
        }
        return result;
    }

    private static Set<String> resolveCompanies(CurrentUser user, String companyCode) {
        if (companyCode == null || companyCode.isBlank()) {
            return user.companies();
        }
        String requested = companyCode.trim().toUpperCase(Locale.ROOT);
        if (!user.companies().contains(requested)) {
            throw new ApiException("当前账号无权查看 " + requested + " 公司，未返回其他公司的派单记录");
        }
        return Set.of(requested);
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
        // 本轮重新预览后，客户端带来的勾选项属于旧卡片，不能污染新范围。
        List<String> uiExcludes = channel != null && channel.hasEmitted(AgentEvent.PREVIEW)
                ? List.of() : ToolContextKeys.uiExcludes(ctx);
        conversationService.logToolCall(conversationId, userId, TOOL_DISPATCH,
                Map.of("previewId", previewId == null ? "" : previewId,
                        "excludeDocNos", excludeDocNos == null ? List.of() : excludeDocNos,
                        "uiExcludes", uiExcludes));
        try {
            CurrentUser user = permissionService.resolve(userId);
            Optional<Snapshot> loaded = (previewId == null || previewId.isBlank())
                    ? previewStore.loadLatest(userId, conversationId)
                    : previewStore.load(userId, previewId);
            if (loaded.isEmpty()) {
                return fail(conversationId, userId, "没有可用的预览快照（不存在或已过期），请先重新查询可派单记录");
            }
            Snapshot snapshot = loaded.get();
            if (conversationId != null && (!Objects.equals(conversationId, snapshot.conversationId())
                    || previewStore.loadLatest(userId, conversationId).filter(s -> s.id().equals(snapshot.id())).isEmpty())) {
                return fail(conversationId, userId, "该预览已作废，请使用本会话最新的预览卡片");
            }
            if (!Objects.equals(snapshot.rulesFingerprint(), ruleCache.fingerprint())) {
                previewStore.delete(userId, snapshot.id());
                return fail(conversationId, userId, "预览之后派单规则已变更，请重新查询可派单记录后再派单");
            }

            // 合并模型给的排除项与前端取消勾选的排除项；单据号不区分大小写
            Set<String> excludes = new LinkedHashSet<>();
            if (excludeDocNos != null) {
                excludeDocNos.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(excludes::add);
            }
            uiExcludes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(excludes::add);
            Map<String, Candidate> byDocNo = new LinkedHashMap<>();
            snapshot.candidates().forEach(c -> byDocNo.put(c.docNo().toUpperCase(Locale.ROOT), c));
            List<String> unmatched = excludes.stream().filter(e -> !byDocNo.containsKey(e.toUpperCase(Locale.ROOT))).toList();
            if (!unmatched.isEmpty()) {
                return fail(conversationId, userId, "以下单据号不在预览结果中，请确认：" + String.join("、", unmatched));
            }
            Set<String> excludedUpper = new LinkedHashSet<>();
            excludes.forEach(e -> excludedUpper.add(e.toUpperCase(Locale.ROOT)));
            List<Candidate> remaining = snapshot.candidates().stream()
                    .filter(c -> !excludedUpper.contains(c.docNo().toUpperCase(Locale.ROOT)))
                    .toList();
            List<String> excludedList = new ArrayList<>();
            excludedUpper.forEach(u -> excludedList.add(byDocNo.get(u).docNo()));
            if (remaining.isEmpty()) {
                return fail(conversationId, userId, "排除之后没有需要派单的记录");
            }

            DispatchPlan plan = planStore.save(snapshot, remaining, excludedList);
            if (props.getDispatch().isRequireConfirm()) {
                PlanPayload payload = new PlanPayload(plan.id(), snapshot.id(), remaining.size(), excludedList, remaining);
                if (channel != null) {
                    channel.emit(AgentEvent.PLAN, payload);
                }
                conversationService.logCard(conversationId, userId, "plan", payload, snapshot.id(), plan.id());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "pending_confirm");
                result.put("planId", plan.id());
                result.put("count", remaining.size());
                result.put("excluded", excludedList);
                result.put("message", "已生成待确认的派单清单，共 " + remaining.size() + " 条。请提示用户在界面的确认卡片上点击\"确认派单\"后才会真正执行。");
                conversationService.logToolResult(conversationId, userId, TOOL_DISPATCH,
                        "pending_confirm planId=" + plan.id() + " count=" + remaining.size(), snapshot.id(), plan.id());
                return result;
            }

            DispatchResultPayload executed = dispatchService.executePlan(user, plan.id());
            if (channel != null) {
                channel.emit(AgentEvent.RESULT, executed);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "done");
            result.put("planId", plan.id());
            result.put("successCount", executed.successCount());
            result.put("failedCount", executed.failedCount());
            result.put("successDocNos", executed.success().stream().map(Candidate::docNo).toList());
            result.put("failed", executed.failed());
            result.put("excluded", excludedList);
            conversationService.logToolResult(conversationId, userId, TOOL_DISPATCH,
                    "done success=" + executed.successCount() + " failed=" + executed.failedCount(), snapshot.id(), plan.id());
            return result;
        } catch (ApiException e) {
            return fail(conversationId, userId, e.getMessage());
        }
    }

    private Map<String, Object> fail(String conversationId, String userId, String message) {
        conversationService.logToolResult(conversationId, userId, TOOL_DISPATCH, "error: " + message, null, null);
        return error(message);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }

    /** 给前端的全量载荷（AgentController 读取快照时也用它） */
    public static PreviewPayload buildPayloadPublic(Snapshot snapshot) {
        return buildPayload(snapshot);
    }

    static PreviewPayload buildPayload(Snapshot snapshot) {
        Map<String, PreviewPayload.ReportCount> counts = new LinkedHashMap<>();
        Map<String, String> rules = new LinkedHashMap<>();
        for (ReportType type : ReportType.values()) {
            List<Candidate> list = snapshot.candidates().stream().filter(c -> c.reportType().equals(type.code())).toList();
            BigDecimal sum = list.stream().map(Candidate::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            counts.put(type.code(), new PreviewPayload.ReportCount(type.code(), type.label(), list.size(), sum));
            list.stream().map(Candidate::ruleDescription).filter(Objects::nonNull).findFirst()
                    .ifPresent(d -> rules.put(type.code(), d));
        }
        return new PreviewPayload(snapshot.id(), snapshot.candidates().size(), new ArrayList<>(counts.values()),
                snapshot.candidates(), rules);
    }

    /** 给模型的精简摘要：每张报表最多 50 条，全量数据不经过模型 */
    private static Map<String, Object> buildModelSummary(Snapshot snapshot, Set<ReportType> filter, List<String> scope) {
        List<Map<String, Object>> byReport = new ArrayList<>();
        for (ReportType type : ReportType.values()) {
            if (!filter.isEmpty() && !filter.contains(type)) {
                continue;
            }
            List<Candidate> list = snapshot.candidates().stream().filter(c -> c.reportType().equals(type.code())).toList();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("reportType", type.code());
            r.put("reportName", type.label());
            r.put("count", list.size());
            r.put("ruleDescription", list.isEmpty() ? null : list.get(0).ruleDescription());
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
        summary.put("previewId", snapshot.id());
        summary.put("total", snapshot.candidates().size());
        summary.put("reportTypes", scope);
        summary.put("byReport", byReport);
        summary.put("note", "完整清单已以表格形式展示给用户，回复时按报表汇总条数并简述规则，不要逐条复述全部记录。");
        return summary;
    }
}
