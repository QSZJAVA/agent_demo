package com.example.report.semantic;

import com.example.report.catalog.CatalogEntry;
import java.util.*;

/** 从当前授权目录生成有界业务能力描述；只描述字段能力，不向模型发送客户清单、数据库表名或权限凭据。 */
public final class SemanticCapabilities {
    private SemanticCapabilities() { }
    /** 当前程序可执行的选择能力；新增能力必须同时增加确定性实现，不能仅通过描述放开执行。 */
    public static Map<String,Object> describe(Map<String,List<String>> selectorsByReport) {
        return Map.of("scopeFilters",List.of("ONE_COMPANY_OR_ALL_AUTHORIZED","REPORT_SET"),
                "recordSelectors",List.of("DOCUMENT","DESCRIPTION","COUNTERPARTY","FIELDS","ALL","REFERENCE"),
                "recordOperations",List.of("EXCLUDE","RESTORE","REPLACE_EXCLUSIONS","RESTORE_ALL","KEEP_ONLY"),
                "keepOnlySelectors",List.of("DOCUMENT","DESCRIPTION","COUNTERPARTY","FIELDS","REFERENCE"),
                "quantifiers",List.of("ONE","ALL","UNSPECIFIED"),
                "selectionBoundary","CURRENT_PREVIEW_OR_FINAL_SCOPE_SNAPSHOT",
                "selectionDefaults",Map.of("action","PREVIEW","reportScope","KEEP_UNLESS_INDEPENDENT_SCOPE_REQUEST"),
                "unsupported",List.of("SORT","LIMIT","MULTI_QUERY_PROGRAM","UNCONFIGURED_FIELD"),
                "selectorsByReport",selectorsByReport);
    }
    /** 只使用调用方已过滤的授权目录；客户能力要求来源声明稳定标识及名称，缺失时不宣传该能力。 */
    public static Map<String,List<String>> selectors(List<CatalogEntry> reports) {
        Map<String,List<String>> result=new LinkedHashMap<>();
        for(var report:reports) {
            var names=report.fields().stream().map(f -> f.name()).toList();
            result.put(report.reportId(),names.containsAll(List.of("counterpartyId","counterpartyName"))
                    ?List.of("DOCUMENT","DESCRIPTION","COUNTERPARTY","FIELDS","ALL","REFERENCE"):List.of("DOCUMENT","DESCRIPTION","FIELDS","ALL","REFERENCE"));
        }
        return Map.copyOf(result);
    }
    /** 当前目录元数据即字段白名单；不把真实字段值或物理映射交给模型。 */
    public static Map<String,List<com.example.report.catalog.query.FieldInfo>> fields(List<CatalogEntry> reports) {
        Map<String,List<com.example.report.catalog.query.FieldInfo>> result=new LinkedHashMap<>();
        for(var report:reports) result.put(report.reportId(),report.fields().stream().filter(f -> com.example.report.rule.FieldFact.supported(f.type()) && !f.name().equals("counterpartyAliases")).toList());
        return Map.copyOf(result);
    }
}
