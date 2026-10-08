package com.example.report.semantic;

import com.example.report.catalog.TextNormalizer;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.catalog.MatchType;
import com.example.report.common.ApiException;
import com.example.report.dispatch.RecordKey;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.Candidate;
import java.util.*;

/** 将记录说法及可选报表限定关联到当前授权预览；只修改排除集合，不查询新报表或执行派单。 */
public final class SelectionResolver {
    private SelectionResolver() { }
    /**
     * 在当前完整快照内解析记录所属报表，限定范围内的修改不影响其他报表的选择。
     * @param rows 已通过会话归属与业务权限检查的当前预览事实
     * @param previous 当前排除集合；最终仍须在完整快照上校验
     * @param change 已通过原文证据校验的RECORDS修改，报表名称由目录解析
     * @param catalog 当前报表目录，不能按名称绕过可派单权限
     * @param user 当前登录用户，决定可解析的报表集合
     * @return 修改后的排除集合；无持久化或派单副作用
     * @throws ApiException 限定报表不可用、不在当前预览或记录不唯一时要求澄清，原集合不变
     */
    public static List<RecordKey> apply(List<Candidate> rows, List<RecordKey> previous, SemanticIntent.ScopeChange change,
                                        ReportCatalogService catalog, CurrentUser user) {
        return apply(rows,previous,change,catalog,user,null);
    }
    /** 在普通选择校验之外核对服务端记录引用；绑定只来自本会话当前预览，失败不修改原集合。 */
    public static List<RecordKey> apply(List<Candidate> rows,List<RecordKey> previous,SemanticIntent.ScopeChange change,
                                        ReportCatalogService catalog,CurrentUser user,DialogueState references) {
        return apply(rows,previous,change,catalog,user,references,change.evidence());
    }
    /** 引用操作同时核对本项证据和整轮原文，避免局部证据遗漏明确对象后复用另一条记录。 */
    public static List<RecordKey> apply(List<Candidate> rows,List<RecordKey> previous,SemanticIntent.ScopeChange change,
                                        ReportCatalogService catalog,CurrentUser user,DialogueState references,String message) {
        if (change.target()!=SemanticIntent.Target.RECORDS) throw new IllegalArgumentException("仅支持记录修改");
        // 未限定报表的恢复全部/替换必须覆盖旧排除集合，才能从范围变化后的失配中恢复。
        if (change.reportMentions().isEmpty()) return applySelection(rows,previous,change,catalog,user,references,message);

        Set<String> reportIds=new LinkedHashSet<>();
        for (String mention:change.reportMentions()) {
            var resolved=catalog.resolve(user,mention);
            if (!resolved.resolved() || resolved.matchType()==MatchType.FUZZY || resolved.matchType()==MatchType.ALL
                    || !resolved.unrecognized().isEmpty())
                throw new ApiException(422,"无法确定记录所属报表“"+mention+"”，请说明完整报表名称");
            resolved.reportIds().forEach(id -> catalog.requireDispatchable(user,id));
            reportIds.addAll(resolved.reportIds());
        }
        var available=rows.stream().map(Candidate::reportId).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(reportIds))
            throw new ApiException(422,"限定报表在当前预览中没有记录，请先明确查询范围");
        var scopedRows=rows.stream().filter(r -> reportIds.contains(r.reportId())).toList();
        var scopedPrevious=previous.stream().filter(k -> reportIds.contains(k.reportId())).toList();
        // REPLACE/CLEAR也只作用于限定报表；不能清除其他报表上已确认的排除选择。
        Set<RecordKey> result=new LinkedHashSet<>(previous.stream().filter(k -> !reportIds.contains(k.reportId())).toList());
        result.addAll(applySelection(scopedRows,scopedPrevious,change,catalog,user,references,message));
        return List.copyOf(result);
    }
    /** 先为每个授权报表编译字段条件，完成全部求值后一次提交集合；任一类型/事实错误不能留下部分选择。 */
    private static List<RecordKey> applySelection(List<Candidate> rows,List<RecordKey> previous,SemanticIntent.ScopeChange change,
                                                 ReportCatalogService catalog,CurrentUser user,DialogueState references,String message) {
        if(change.selectorKind()==SemanticIntent.SelectorKind.REFERENCE) {
            var matched=SelectionReferences.resolve(references,rows,change);
            return updateSelection(rows,previous,change,matched);
        }
        // 整类取消选择只作用于传入的授权快照或报表子集，不用虚构恒真字段条件，也不改变报表查询范围。
        if(change.selectorKind()==SemanticIntent.SelectorKind.ALL) {
            Set<RecordKey> result=new LinkedHashSet<>(previous);
            var matched=rows.stream().map(row->new RecordKey(row.reportId(),row.recordId())).toList();
            if(change.operation()==SemanticIntent.Operation.RESTORE)result.removeAll(matched);else result.addAll(matched);
            return List.copyOf(result);
        }
        if(change.selectorKind()!=SemanticIntent.SelectorKind.FIELDS) return applyTyped(rows,previous,change);
        Map<String,List<com.example.report.catalog.query.FieldInfo>> fields=new HashMap<>();
        for(String reportId:rows.stream().map(Candidate::reportId).distinct().toList())fields.put(reportId,catalog.requireDispatchable(user,reportId).fields());
        Map<String,List<SemanticIntent.ConditionGroup>> applicable=new HashMap<>();
        // 不同报表可以有不同字段。每个 AND 组必须能在至少一张当前报表完整求值；未知字段或跨来源拼接仍拒绝。
        // 未声明字段的报表不匹配该组，尤其不能把“未声明”当作业务 NULL，误选其他报表。
        for(var group:change.conditions()) {
            var matching=fields.entrySet().stream().filter(entry->group.allOf().stream().allMatch(condition->entry.getValue().stream().anyMatch(f->f.name().equals(condition.field())))).toList();
            if(matching.isEmpty())throw new ApiException(422,"字段条件组合未在当前任何报表中完整配置，请明确所属报表或检查字段");
            for(var entry:matching)applicable.computeIfAbsent(entry.getKey(),ignored->new ArrayList<>()).add(group);
        }
        Map<String,java.util.function.Predicate<Candidate>> filters=new HashMap<>();
        for(var entry:fields.entrySet())filters.put(entry.getKey(),applicable.containsKey(entry.getKey())
                ?FieldSelection.compile(applicable.get(entry.getKey()),entry.getValue()):row->false);
        var matched=rows.stream().filter(row -> filters.get(row.reportId()).test(row)).map(row -> new RecordKey(row.reportId(),row.recordId())).toList();
        if(change.quantifier()!=SemanticIntent.Quantifier.ALL && matched.size()!=1)
            throw new ApiException(422,"条件匹配"+matched.size()+"条，请明确全部匹配或指定单据");
        return updateSelection(rows,previous,change,matched);
    }
    /** 在完整授权快照中先解析实体再展开记录集合；多客户歧义不能用ALL绕过，任何一项失败整次选择不提交。 */
    private static List<RecordKey> applyTyped(List<Candidate> rows,List<RecordKey> previous,SemanticIntent.ScopeChange change) {
        if(change.operation()==SemanticIntent.Operation.RESTORE_ALL) return List.of();
        Set<RecordKey> matched=new LinkedHashSet<>();
        for(String mention:change.mentions()) {
            String term=TextNormalizer.normalize(mention);
            var entityTerms=entityTerms(term,change.selectorKind());
            List<Candidate> found;
            if(change.selectorKind()==SemanticIntent.SelectorKind.COUNTERPARTY) {
                var entities=rows.stream().filter(r -> r.counterparty()!=null &&
                        (entityTerms.contains(TextNormalizer.normalize(r.counterparty().name())) || r.counterparty().aliases().stream().anyMatch(a -> entityTerms.contains(TextNormalizer.normalize(a)))))
                        .map(r -> List.of(r.companyCode(),r.counterparty().id())).distinct().toList();
                if(entities.size()!=1) {
                    String candidates=rows.stream().filter(r -> r.counterparty()!=null && term.equals(TextNormalizer.normalize(r.counterparty().name())))
                            .limit(5).map(r -> r.counterparty().name()+"（公司"+r.companyCode()+"，单据"+r.docNo()+"）").distinct().collect(java.util.stream.Collectors.joining("、"));
                    throw new ApiException(422,"“"+mention+"”未唯一定位客户，请提供已维护的客户全称、别名或单据号"+(candidates.isEmpty()?"":"；候选："+candidates));
                }
                var key=entities.get(0);
                found=rows.stream().filter(r -> r.counterparty()!=null && Objects.equals(r.companyCode(),key.get(0)) && r.counterparty().id().equals(key.get(1))).toList();
            } else {
                found=rows.stream().filter(r -> entityTerms.contains(TextNormalizer.normalize(r.docNo()))).toList();
                if(found.isEmpty() && change.selectorKind()==SemanticIntent.SelectorKind.DESCRIPTION)
                    found=rows.stream().filter(r -> r.label()!=null && TextNormalizer.normalize(r.label()).contains(term)).toList();
            }
            if(found.isEmpty()) throw new ApiException(422,"“"+mention+"”在当前预览中没有匹配记录，请核对对象或重新查询");
            if(found.size()>1 && (change.selectorKind()==SemanticIntent.SelectorKind.DOCUMENT || change.quantifier()!=SemanticIntent.Quantifier.ALL))
                throw new ApiException(422,"“"+mention+"”匹配"+found.size()+"条记录，请说明全部匹配记录或指定单据号");
            for(var row:found) matched.add(new RecordKey(row.reportId(),row.recordId()));
        }
        return updateSelection(rows,previous,change,matched);
    }
    /** 各种已验证定位方式共享集合操作；KEEP_ONLY只在当前授权且可选的报表子集取补集，不能扩大定位数量或清除其他报表选择。 */
    private static List<RecordKey> updateSelection(List<Candidate> rows,List<RecordKey> previous,SemanticIntent.ScopeChange change,Collection<RecordKey> matched) {
        Set<RecordKey> result=new LinkedHashSet<>(change.operation()==SemanticIntent.Operation.REPLACE_EXCLUSIONS?List.of():previous);
        if(change.operation()==SemanticIntent.Operation.KEEP_ONLY) {
            var keep=new HashSet<>(matched);result.clear();
            rows.stream().map(row->new RecordKey(row.reportId(),row.recordId())).filter(key->!keep.contains(key)).forEach(result::add);
        } else if(change.operation()==SemanticIntent.Operation.RESTORE)result.removeAll(matched);else result.addAll(matched);
        return List.copyOf(result);
    }
    /**
     * 仅为已识别的实体类型生成至多一个去称谓候选，不解析动作、不截断公司名或模糊匹配。
     * 原文与去称谓候选同时参与精确关联：若称谓恰好属于另一实体的真实名称，必须保留歧义，不能优先选中其中一家。
     */
    private static Set<String> entityTerms(String normalized,SemanticIntent.SelectorKind kind) {
        String pattern=switch(kind) {
            case COUNTERPARTY -> "^(?:交易对方|客户)";
            case DOCUMENT -> "^(?:报销单号|单据号|订单号|发票号|报销单|单据|订单|发票)";
            default -> null;
        };
        if(pattern==null) return Set.of(normalized);
        String unwrapped=normalized.replaceFirst(pattern,"");
        return unwrapped.isBlank() || unwrapped.equals(normalized)?Set.of(normalized):Set.of(normalized,unwrapped);
    }
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
