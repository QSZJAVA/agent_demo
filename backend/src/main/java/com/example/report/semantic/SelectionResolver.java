package com.example.report.semantic;

import com.example.report.catalog.TextNormalizer;
import com.example.report.common.ApiException;
import com.example.report.dispatch.RecordKey;
import com.example.report.rule.Candidate;
import java.util.*;

/** Match a record once, then keep its compound identity through preview and plan creation. */
public final class SelectionResolver {
    private SelectionResolver() { }
    public static List<RecordKey> apply(List<Candidate> rows, List<RecordKey> previous, SemanticIntent.Change change) {
        if (change.operation()==SemanticIntent.Operation.KEEP) return List.copyOf(previous);
        if (change.operation()==SemanticIntent.Operation.CLEAR) return List.of();
        Set<RecordKey> keys = new LinkedHashSet<>();
        for (String mention : change.mentions()) {
            String term=TextNormalizer.normalize(mention);
            var matches=rows.stream().filter(r -> term.equals(TextNormalizer.normalize(r.docNo()))).toList();
            if (matches.isEmpty()) matches=rows.stream().filter(r -> r.label()!=null && TextNormalizer.normalize(r.label()).contains(term)).toList();
            if (matches.size()!=1) throw new ApiException(422,"“"+mention+"”未能唯一定位记录，请在预览表格中勾选要派单的记录");
            var row=matches.get(0); keys.add(new RecordKey(row.reportId(),row.recordId()));
        }
        Set<RecordKey> result=new LinkedHashSet<>(change.operation()==SemanticIntent.Operation.REPLACE ? List.of() : previous);
        if (change.operation()==SemanticIntent.Operation.REMOVE) result.removeAll(keys); else result.addAll(keys);
        return List.copyOf(result);
    }
    public static void validate(List<Candidate> rows, List<RecordKey> keys) {
        var available=rows.stream().map(r -> new RecordKey(r.reportId(),r.recordId())).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(keys)) throw new ApiException(422,"勾选记录与当前预览不一致，请重新选择");
    }
}
