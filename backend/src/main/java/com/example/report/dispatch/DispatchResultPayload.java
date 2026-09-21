package com.example.report.dispatch;

import com.example.report.rule.Candidate;

import java.util.List;

/**
 * 派单结果卡片载荷（前端渲染 + 对话日志 card）
 */
public record DispatchResultPayload(
        String planId,
        String previewId,
        int total,
        int successCount,
        int failedCount,
        List<Candidate> success,
        List<FailedRecord> failed
) {
    public record FailedRecord(String reportType, String reportName, String docNo, String companyCode, String message) {
    }
}
