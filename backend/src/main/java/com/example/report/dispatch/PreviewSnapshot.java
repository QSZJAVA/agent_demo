package com.example.report.dispatch;

import com.example.report.common.JsonUtil;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;
import com.example.report.rule.Candidate;

import java.util.List;
import java.util.Map;

/**
 * 预览快照：状态 + 记录
 * @param preview 权威预览状态或其展示载荷
 * @param items 所属预览或清单内的有序条目
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

    /** 精确记录预览的身份边界；普通范围预览为空，刷新时不能扩大已绑定目标。 */
    public List<RecordTarget> targets() {
        Object value=query().get("targetRecords");
        return value==null?List.of():JsonUtil.MAPPER.convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<RecordTarget>>(){});
    }

    /** 精确预览的原始查询引用；普通范围预览为空。 */
    public String targetSourceRef() {return (String)query().get("targetSourceRef");}

    public static Candidate toCandidate(DispatchPreviewItem i) {
        return new Candidate(i.getReportId(), i.getReportName(), i.getRecordId(), i.getDocNo(), i.getCompanyCode(),
                i.getLabel(), i.getAmount(), i.getBizDate(), i.getRuleId(), i.getRuleName(), i.getRuleVersion(),
                i.getRuleDescription(), i.getCatalogVersion(), com.example.report.rule.CounterpartyRef.fromSnapshot(i.getCounterpartyJson()), com.example.report.rule.FieldFact.restore(i.getFieldsJson()));
    }
}
