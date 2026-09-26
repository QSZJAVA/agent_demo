package com.example.report.dispatch;

import com.example.report.common.JsonUtil;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;
import com.example.report.rule.Candidate;

import java.util.List;
import java.util.Map;

/**
 * 预览快照：状态 + 记录
 */
public record PreviewSnapshot(DispatchPreview preview, List<DispatchPreviewItem> items) {

    public List<Candidate> candidates() {
        return items.stream().map(PreviewSnapshot::toCandidate).toList();
    }

    public List<String> reportIds() {
        return DispatchVersionService.reportIds(preview);
    }

    public Map<String, Object> query() {
        return JsonUtil.toMap(preview.getQueryJson());
    }

    public static Candidate toCandidate(DispatchPreviewItem i) {
        return new Candidate(i.getReportId(), i.getReportName(), i.getRecordId(), i.getDocNo(), i.getCompanyCode(),
                i.getLabel(), i.getAmount(), i.getBizDate(), i.getRuleId(), i.getRuleName(), i.getRuleVersion(),
                i.getRuleDescription(), i.getCatalogVersion());
    }
}
