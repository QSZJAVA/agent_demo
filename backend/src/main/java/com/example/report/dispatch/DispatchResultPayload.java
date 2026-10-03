package com.example.report.dispatch;

import com.example.report.rule.Candidate;

import java.util.List;

/**
 * 派单结果卡片载荷（前端渲染 + 对话日志 card）
 *
 * @param replayed 重复确认同一份已执行清单时为 true：返回的是第一次的结果，没有再次调用派单接口
 * @param planId 派单清单标识，关联服务端持久化清单
 * @param previewId 预览标识，选择和建单必须绑定此快照
 * @param total 授权范围内统计总数，不能用当前页长度代替
 * @param successCount 成功派单的记录数
 * @param failedCount 非成功记录统计，具体结果见失败条目或待核对状态
 * @param retryableCount 业务接口已明确失败且允许重试的条目数；未知结果不能直接重发
 * @param success 有界成功记录样本，最多50条
 * @param failed 有界非成功记录摘要，最多50条；完整记录从清单分页读取
 */
public record DispatchResultPayload(
        String planId,
        String previewId,
        int total,
        int successCount,
        int failedCount,
        int retryableCount,
        List<Candidate> success,
        List<FailedRecord> failed,
        boolean replayed
) {
    public DispatchResultPayload {
        success = success == null ? List.of() : List.copyOf(success.subList(0, Math.min(success.size(), 50)));
        failed = failed == null ? List.of() : List.copyOf(failed.subList(0, Math.min(failed.size(), 50)));
    }
    /**
     * @param outcome FAILED（明确失败）/ SKIPPED（复核未通过，未发送）/ UNKNOWN（结果不明，须先核对）
     * @param reportId 稳定报表标识，关联报表目录
     * @param reportName 报表展示名称
     * @param docNo 来源业务单据号，可空时表示来源未提供
     * @param companyCode 公司代码；查询范围为空时表示当前用户全部可见公司
     * @param errorCode 业务失败原因编码
     * @param message 可展示的操作摘要或失败原因，禁止包含凭据
     */
    public record FailedRecord(String reportId, String reportName, String docNo, String companyCode, String outcome,
                               String errorCode, String message) {
    }
}
