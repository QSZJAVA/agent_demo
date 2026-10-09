package com.example.report.semantic;

import com.example.report.dispatch.RecordKey;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话语义状态：desired 为通过任务预检后尝试处理的范围，effective 为最近成功查询的范围，两者不能因执行失败而混同。
 * 标识和选择由服务端维护；未澄清范围阻止后续省略表达沿用过时事实执行。
 */
@Data
public class DialogueState {
    public enum Phase { READY, QUERYING, REJECTED, CLARIFY, PLAN_READY, FAILED }
    /**
     * 用户查询范围；仅支持单家公司或当前授权的全部公司。
     * @param companyCode 公司代码；查询范围为空时表示当前用户全部可见公司
     * @param allReports 是否使用当前用户全部可派单报表；false时使用reportIds
     * @param reportIds 明确指定的稳定报表标识集合，服务端须验证业务授权
     */
    public record Scope(String companyCode, boolean allReports, List<String> reportIds) {
        public Scope { reportIds = reportIds == null ? List.of() : List.copyOf(reportIds); }
        public static Scope initial() { return new Scope(null, true, List.of()); }
    }
    /** 最近通过任务预检的查询范围；执行失败时仍保留，未通过预检的草稿不得覆盖，也不能视为已生效范围。 */
    private Scope desired = Scope.initial();
    /** 最近成功查询的范围；未有成功快照时为空。 */
    private Scope effective;
    /** 本轮处理阶段：就绪、查询、拒绝、澄清、清单就绪或失败。 */
    private Phase phase = Phase.READY;
    /** 最近成功预览标识；使用前仍需检查快照有效性。 */
    private String previewId;
    /** 当前待确认或已执行清单标识；未生成时为空。 */
    private String planId;
    /** 绑定报表与记录的排除集合，只用于当前有效事实范围。 */
    private List<RecordKey> excludedRecords = List.of();
    /** 最近通过校验的意图；不构成实际派单授权。 */
    private SemanticIntent pendingIntent;
    /** 本轮意图解析来源；尚未解析时为空。 */
    private IntentParser.Source parserSource;
    /** 报表范围未澄清标记；禁止省略表达直接沿用旧报表。 */
    private boolean unresolvedReports;
    /** 公司范围未澄清标记；禁止省略表达直接沿用旧公司。 */
    private boolean unresolvedCompany;
    /** 记录选择未澄清标记；生成清单前必须解决。 */
    private boolean unresolvedRecords;
    /** 上轮请求整体未完成；供统一规划和语义复核判断恢复要求，不等于已绑定对象失效。 */
    private boolean unresolvedRequest;
    /** 最近澄清、拒绝或失败原因；成功轮次清空。 */
    private String lastReason;
    /** 最近一次尝试处理的应用时间。 */
    private LocalDateTime attemptedAt;
    /** 最近最多4条脱敏用户消息，供有限上下文与恢复使用。 */
    private List<String> recentUserMessages = List.of();
    /** 最近成功的只读业务查询，独立于派单范围；首次查询前为空。 */
    private com.example.report.assistant.BusinessQuery businessQuery;
    /** 最近成功查询当前页的有序业务标识及已展示标量字段，供明确指代使用，不包含未展示记录或新增权限。 */
    private List<java.util.Map<String,String>> businessReferences = List.of();
    /** 最近成功只读查询的完整匹配条数；首次查询前为null，不以当前页长度猜测全集大小。 */
    private Long businessTotalCount;
    /** 最近助手路由：BUSINESS_QUERY、DISPATCH、HELP或CLARIFY；尚未处理为空。 */
    private String assistantRoute;
    /** 当前业务焦点：最近使用的只读查询或派单规划；帮助、拒绝和澄清不清除已有焦点。 */
    private String assistantFocus;
    /** 最近业务查询未完成时为true，模糊追问不能沿用旧结果冒充成功。 */
    private boolean businessUnresolved;
    /** 会话首次查询事实的权限版本，后续权限变化需要新建会话，避免历史统计泄露已撤销范围。 */
    private String businessPermissionVersion;
    /** 本会话曾查询过的报表范围并集，恢复历史时逐个复核，不能只检查最后一次查询。 */
    private List<String> businessReportIds = List.of();
    /** 本会话曾查询公司范围的并集；管理员经派单追溯读取会话时也必须完整具备这些公司权限。 */
    private List<String> businessCompanyCodes = List.of();
    /** 候选之后发生过业务查询时为true；旧候选须重新展示再建单，具体查询记录可另行绑定并重新核验。 */
    private boolean businessQueryAfterPreview;
    /** 最近成功应用的记录选择及其实体证据；供跨轮唯一指代使用，新范围会清除，失败草稿不得覆盖。 */
    private List<SemanticIntent.ScopeChange> lastSuccessfulSelection = List.of();
    /** 最近一次成功选择实际定位的公开单据和字段事实，最多50条；不包含未定位对象，也不是下一轮动作。 */
    private List<java.util.Map<String,String>> lastSelectionReferences = List.of();
    /** 上述引用是否完整；超过条数或32KiB预算时为false，模型不能把保留的部分引用当作全部目标。 */
    private boolean lastSelectionReferencesComplete = true;
    /** 最近选择引用对应的预览标识；为空表示没有可使用的记录引用，刷新后不得跨预览复用。 */
    private String lastSelectionPreviewId;
    /** 不出站的引用绑定：随机引用键到当前预览复合记录标识，随选择引用一起替换。 */
    private java.util.Map<String,RecordKey> lastSelectionReferenceKeys = java.util.Map.of();
}
