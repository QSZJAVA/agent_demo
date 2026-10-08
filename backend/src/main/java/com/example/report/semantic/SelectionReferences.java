package com.example.report.semantic;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.dispatch.RecordKey;
import com.example.report.rule.Candidate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 已成功定位记录的有界引用；模型只看到随机键和公开事实，真实记录与预览绑定由服务端保管。 */
public final class SelectionReferences {
    private SelectionReferences() { }
    /** 给准备清单提供当前已选对象事实；完整计数不受展示截断影响，公开字段仍由模型出站脱敏统一处理。 */
    public static Map<String,Object> describeSelection(DialogueState state,List<Candidate> rows) {
        var excluded=new HashSet<>(state.getExcludedRecords());var selected=rows.stream()
                .filter(r->!excluded.contains(new RecordKey(r.reportId(),r.recordId()))).toList();
        var facts=new ArrayList<Map<String,String>>();int bytes=0;
        for(var row:selected) {
            var ref=new LinkedHashMap<String,String>();ref.put("reportName",row.reportName());
            if(row.docNo()!=null)ref.put("docNo",row.docNo());if(row.label()!=null)ref.put("label",row.label());
            for(var field:row.fields())if(field.value()!=null)ref.putIfAbsent(field.name(),field.value());
            int size=JsonUtil.toJson(ref).getBytes(StandardCharsets.UTF_8).length;
            if(facts.size()>=50 || bytes+size>32768)break;
            facts.add(ref);bytes+=size;
        }
        return Map.of("totalCount",rows.size(),"selectedCount",selected.size(),"selectedRows",facts,"complete",facts.size()==selected.size());
    }
    /** 替换最近成功定位的引用；先构建完整局部结果再写状态，不保留超预算对象的可执行键。 */
    public static void capture(DialogueState state,List<Candidate> rows,Set<RecordKey> keys) {
        var facts=new ArrayList<Map<String,String>>();var bindings=new LinkedHashMap<String,RecordKey>();
        int bytes=0;boolean complete=true;
        for(var row:rows)if(keys.contains(new RecordKey(row.reportId(),row.recordId()))) {
            String reference="ref_"+JsonUtil.newId();var ref=new LinkedHashMap<String,String>();ref.put("referenceKey",reference);
            ref.put("reportId",row.reportId());ref.put("reportName",row.reportName());ref.put("companyCode",row.companyCode());
            if(row.docNo()!=null)ref.put("docNo",row.docNo());if(row.label()!=null)ref.put("label",row.label());
            for(var field:row.fields())if(field.value()!=null)ref.putIfAbsent(field.name(),field.value());
            if(row.counterparty()!=null){ref.put("counterpartyId",row.counterparty().id());ref.put("counterpartyName",row.counterparty().name());}
            int size=JsonUtil.toJson(ref).getBytes(StandardCharsets.UTF_8).length;
            if(facts.size()>=50 || bytes+size>32768){complete=false;break;}
            facts.add(ref);bindings.put(reference,new RecordKey(row.reportId(),row.recordId()));bytes+=size;
        }
        state.setLastSelectionReferences(List.copyOf(facts));state.setLastSelectionReferencesComplete(complete);
        state.setLastSelectionReferenceKeys(Map.copyOf(bindings));state.setLastSelectionPreviewId(state.getPreviewId());
    }
    /** 先验证所有引用的来源、当前候选及数量；未知、跨预览、跨报表或单笔歧义均拒绝，不模糊匹配名称。 */
    public static Set<RecordKey> resolve(DialogueState state,List<Candidate> rows,SemanticIntent.ScopeChange change) {
        if(state==null || state.getPreviewId()==null || !Objects.equals(state.getPreviewId(),state.getLastSelectionPreviewId()))
            throw new ApiException(422,"记录引用已失效，请在当前预览重新指定对象");
        var available=rows.stream().map(r->new RecordKey(r.reportId(),r.recordId())).collect(java.util.stream.Collectors.toSet());
        var matched=new LinkedHashSet<RecordKey>();
        for(String mention:change.mentions()) {
            var key=state.getLastSelectionReferenceKeys().get(mention);
            if(key==null || !available.contains(key))throw new ApiException(422,"记录引用未知或不属于当前限定范围，不能扩大选择");
            matched.add(key);
        }
        if(matched.isEmpty() || (change.quantifier()!=SemanticIntent.Quantifier.ALL && matched.size()!=1))
            throw new ApiException(422,"记录引用未唯一定位，请明确单笔或全部匹配记录");
        return Set.copyOf(matched);
    }
    /** 预览或手工选择变化后失效全部引用，不让旧键作用于新快照。 */
    public static void clear(DialogueState state) {
        state.setLastSuccessfulSelection(List.of());state.setLastSelectionReferences(List.of());state.setLastSelectionReferencesComplete(true);
        state.setLastSelectionPreviewId(null);state.setLastSelectionReferenceKeys(Map.of());
    }
}
