package com.example.report.semantic;

import com.example.report.dispatch.RecordKey;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话语义状态：desired 为用户最近提出的范围，effective 为最近成功查询的范围，两者不能因失败而混同。
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
    /** 用户最近请求的查询范围；查询失败时仍保留，不能当作已成功生效范围。 */
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
    /** 最近澄清、拒绝或失败原因；成功轮次清空。 */
    private String lastReason;
    /** 最近一次尝试处理的应用时间。 */
    private LocalDateTime attemptedAt;
    /** 最近最多4条脱敏用户消息，供有限上下文与恢复使用。 */
    private List<String> recentUserMessages = List.of();
}
