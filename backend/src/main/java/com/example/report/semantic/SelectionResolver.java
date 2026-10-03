package com.example.report.semantic;

import com.example.report.catalog.TextNormalizer;
import com.example.report.common.ApiException;
import com.example.report.dispatch.RecordKey;
import com.example.report.rule.Candidate;
import java.util.*;

/** 将用户记录说法唯一关联到当前预览，并用报表与记录复合标识保存排除选择。 */
public final class SelectionResolver {
    private SelectionResolver() { }
    /**
     * 在当前快照上修改排除集合：ADD排除，REMOVE恢复，REPLACE替换，CLEAR清空。
     * @param rows 当前成功预览的完整候选事实
     * @param previous 前一版排除选择
     * @param change 已校验的记录修改及原文实体
     * @return 修改后的不可变复合标识集合
     * @throws ApiException 单据或摘要无法唯一定位时要求用户在表格中选择，不能任取首个匹配
     */
    public static List<RecordKey> apply(List<Candidate> rows, List<RecordKey> previous, SemanticIntent.Change change) {
        if (change.operation()==SemanticIntent.Operation.KEEP) return List.copyOf(previous);
        if (change.operation()==SemanticIntent.Operation.CLEAR) return List.of();
        Set<RecordKey> keys = new LinkedHashSet<>();
        for (String mention : change.mentions()) {
            String term=TextNormalizer.normalize(mention);
            var matches=rows.stream().filter(r -> term.equals(TextNormalizer.normalize(r.docNo()))).toList();
            if (matches.isEmpty()) {
                // Entity linking only: remove a document-kind label, never interpret action/negation here.
                String identifier=term.replaceFirst("^(?:报销单号|单据号|订单号|发票号|报销单|单据|订单|发票)","");
                if (!identifier.equals(term) && !identifier.isBlank())
                    matches=rows.stream().filter(r -> identifier.equals(TextNormalizer.normalize(r.docNo()))).toList();
            }
            if (matches.isEmpty()) matches=rows.stream().filter(r -> r.label()!=null && TextNormalizer.normalize(r.label()).contains(term)).toList();
            if (matches.size()!=1) throw new ApiException(422,"“"+mention+"”未能唯一定位记录，请在预览表格中勾选要派单的记录");
            var row=matches.get(0); keys.add(new RecordKey(row.reportId(),row.recordId()));
        }
        Set<RecordKey> result=new LinkedHashSet<>(change.operation()==SemanticIntent.Operation.REPLACE ? List.of() : previous);
        if (change.operation()==SemanticIntent.Operation.REMOVE) result.removeAll(keys); else result.addAll(keys);
        return List.copyOf(result);
    }
    /** 生成清单前要求所有排除标识都属于当前快照；跨预览选择不能静默忽略或移用于另一批事实。 */
    public static void validate(List<Candidate> rows, List<RecordKey> keys) {
        var available=rows.stream().map(r -> new RecordKey(r.reportId(),r.recordId())).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(keys)) throw new ApiException(422,"勾选记录与当前预览不一致，请重新选择");
    }
}
