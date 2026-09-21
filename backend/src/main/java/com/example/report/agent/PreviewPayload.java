package com.example.report.agent;

import com.example.report.rule.Candidate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 预览卡片载荷（前端渲染带勾选框的表格 + 对话日志 card）
 */
public record PreviewPayload(
        String previewId,
        int total,
        List<ReportCount> byReport,
        List<Candidate> records,
        Map<String, String> ruleDescriptions
) {
    public record ReportCount(String reportType, String reportName, int count, BigDecimal amount) {
    }
}
