package com.example.report.agent;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.dispatch.PreviewSnapshot;
import com.example.report.common.JsonUtil;
import com.example.report.rule.Candidate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 预览卡片载荷（前端渲染带勾选框的表格 + 对话日志 card）。
 * status 是生成时的状态；之后的状态以服务端卡片状态接口为准。
 *
 * @param byReport         预览范围内每张报表的条数与金额（0 条的报表也列出）
 * @param ruleDescriptions report_id → 命中规则说明
 * @param resolution       报表是怎么识别出来的（精确 / 别名 / 模糊 / 全部 / 选择）
 * @param previewId 预览标识，选择和建单必须绑定此快照
 * @param status 当前业务状态，以所属状态机为准
 * @param total 授权范围内统计总数，不能用当前页长度代替
 * @param totalAmount 候选业务金额合计，币种沿用来源账本
 * @param records 当前对象的有界业务记录集合
 * @param createdAt 记录创建时间
 * @param expiresAt 有效期截止时间，到期后须重新校验或创建
 */
public record PreviewPayload(
        String previewId,
        String status,
        int total,
        BigDecimal totalAmount,
        List<ReportCount> byReport,
        List<Candidate> records,
        Map<String, String> ruleDescriptions,
        Resolution resolution,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {
    public static final int PAGE_SIZE = 50;
    /**
     * 单报表的预览数量与金额汇总。
     * @param reportId 稳定报表标识，关联报表目录
     * @param reportName 报表展示名称
     * @param count 当前业务对象的记录数量
     * @param amount 业务金额，保留精确十进制；币种沿用来源账本
     */
    public record ReportCount(String reportId, String reportName, int count, BigDecimal amount) {
    }

    /**
     * 报表解析展示证据。
     * @param matchType 名称匹配类型；歧义或未识别不能自动执行
     * @param query 用户对报表的说法或本次解析输入
     * @param matchedTerms 在输入中命中的目录词条
     * @param unrecognized 未被可靠解析的输入部分，不能静默丢弃
     */
    public record Resolution(String matchType, String query, List<String> matchedTerms, List<String> unrecognized) {
    }

    @SuppressWarnings("unchecked")
    public static PreviewPayload of(PreviewSnapshot snapshot, ReportCatalogService catalogService) {
        List<Candidate> records = snapshot.candidates();
        Map<String, ReportCount> counts = new LinkedHashMap<>();
        Map<String, String> rules = new LinkedHashMap<>();
        for (String reportId : snapshot.reportIds()) {
            List<Candidate> list = records.stream().filter(c -> c.reportId().equals(reportId)).toList();
            String name = list.isEmpty()
                    ? catalogService.find(reportId).map(CatalogEntry::reportName).orElse(reportId)
                    : list.get(0).reportName();
            BigDecimal sum = list.stream().map(Candidate::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            counts.put(reportId, new ReportCount(reportId, name, list.size(), sum));
            list.stream().map(Candidate::ruleDescription).filter(Objects::nonNull).findFirst().ifPresent(d -> rules.put(reportId, d));
        }
        Map<String, Object> query = snapshot.query();
        String summaryJson = snapshot.preview().getSummaryJson();
        if (summaryJson != null && !summaryJson.isBlank()) {
            Map<String, Object> summary = JsonUtil.toMap(summaryJson);
            Object byReport = summary.get("byReport");
            if (byReport != null) {
                List<ReportCount> saved = JsonUtil.MAPPER.convertValue(byReport, new TypeReference<List<ReportCount>>() {});
                counts.clear();
                saved.forEach(c -> counts.put(c.reportId(), c));
            }
            Object descriptions = summary.get("ruleDescriptions");
            if (descriptions instanceof Map<?, ?> saved) {
                rules.clear();
                saved.forEach((k, v) -> { if (k != null && v != null) rules.put(k.toString(), v.toString()); });
            }
        }
        Resolution resolution = new Resolution((String) query.get("matchType"), (String) query.get("reportQuery"),
                (List<String>) query.getOrDefault("matchedTerms", List.of()),
                (List<String>) query.getOrDefault("unrecognized", List.of()));
        return new PreviewPayload(snapshot.preview().getId(), snapshot.preview().getStatus(), snapshot.preview().getTotalCount(),
                snapshot.preview().getTotalAmount(), new ArrayList<>(counts.values()), records.stream().limit(PAGE_SIZE).toList(), rules, resolution,
                snapshot.preview().getCreatedAt(), snapshot.preview().getExpiresAt());
    }
}
