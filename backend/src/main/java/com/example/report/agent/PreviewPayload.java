package com.example.report.agent;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.dispatch.PreviewSnapshot;
import com.example.report.rule.Candidate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 预览卡片载荷（前端渲染带勾选框的表格 + 对话日志 card）。
 * status 是生成时的状态；之后的状态以服务端卡片状态接口为准。
 *
 * @param byReport         预览范围内每张报表的条数与金额（0 条的报表也列出）
 * @param ruleDescriptions report_id → 命中规则说明
 * @param resolution       报表是怎么识别出来的（精确 / 别名 / 模糊 / 全部 / 选择）
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
    public record ReportCount(String reportId, String reportName, int count, BigDecimal amount) {
    }

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
        Resolution resolution = new Resolution((String) query.get("matchType"), (String) query.get("reportQuery"),
                (List<String>) query.getOrDefault("matchedTerms", List.of()),
                (List<String>) query.getOrDefault("unrecognized", List.of()));
        return new PreviewPayload(snapshot.preview().getId(), snapshot.preview().getStatus(), records.size(),
                snapshot.preview().getTotalAmount(), new ArrayList<>(counts.values()), records, rules, resolution,
                snapshot.preview().getCreatedAt(), snapshot.preview().getExpiresAt());
    }
}
