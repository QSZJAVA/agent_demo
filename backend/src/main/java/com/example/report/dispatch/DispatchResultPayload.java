package com.example.report.dispatch;

import com.example.report.rule.Candidate;

import java.util.List;

/**
 * 派单结果卡片载荷（前端渲染 + 对话日志 card）
 *
 * @param replayed 重复确认同一份已执行清单时为 true：返回的是第一次的结果，没有再次调用派单接口
 */
public record DispatchResultPayload(
        String planId,
        String previewId,
        int total,
        int successCount,
        int failedCount,
        List<Candidate> success,
        List<FailedRecord> failed,
        boolean replayed
) {
    /**
     * @param outcome FAILED（派单接口失败）/ SKIPPED（执行前复核不通过，未调用派单接口）
     */
    public record FailedRecord(String reportId, String reportName, String docNo, String companyCode, String outcome,
                               String errorCode, String message) {
    }
}
