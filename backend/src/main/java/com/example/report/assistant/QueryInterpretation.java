package com.example.report.assistant;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import com.example.report.rule.FieldFact;
import java.math.BigDecimal;
import java.util.*;

/** 完整查询的条件解释与差异投影；保留AND/OR组结构，精确标量规范化仅用于比较，不改写执行查询。 */
public final class QueryInterpretation {
    private QueryInterpretation() { }
    /** 按目录类型规范化查询摘要，用于绑定结构化编辑；字符串标识不能当作数字去掉前导零。 */
    public static String fingerprint(BusinessQuery query,List<FieldInfo> columns){
        var copy=new LinkedHashMap<>(JsonUtil.toMap(JsonUtil.toJson(query)));copy.put("reportIds",query.reportIds().stream().sorted().toList());
        copy.put("conditions",groups(query,columns));return Digests.sha256(JsonUtil.toJson(copy));
    }
    /**
     * 生成只读解释；缺少模型依据明确标注，不用程序推断冒充模型已复核来源。
     * @param before 上一成功查询，首次或无成功查询时为空
     * @param after 本轮通过全部校验的实际查询
     * @param columns 来源字段类型和说明
     * @param review 通过独立复核的依据，纯程序调用时可能为空
     * @param reference 本轮服务端请求标识，作为编辑的当前版本引用
     * @return 可持久化的条件变化与来源说明
     */
    public static Map<String,Object> describe(BusinessQuery before,BusinessQuery after,List<FieldInfo> columns,SemanticReview review,String reference){
        var old=before==null?Map.<String,BusinessQuery.Filter>of():atoms(before,columns);var current=atoms(after,columns);
        var added=new ArrayList<BusinessQuery.Filter>();var removed=new ArrayList<BusinessQuery.Filter>();var retained=new ArrayList<BusinessQuery.Filter>();
        current.forEach((key,condition)->{if(old.containsKey(key))retained.add(condition);else added.add(condition);});old.forEach((key,condition)->{if(!current.containsKey(key))removed.add(condition);});
        var evidence=new ArrayList<Map<String,Object>>();
        for(var condition:current.values()){
            var check=review==null?null:review.conditionChecks().stream().filter(c->atom(c.condition(),columns).equals(atom(condition,columns))).findFirst().orElse(null);
            evidence.add(Map.of("condition",condition,"origin",check==null?"UNAVAILABLE":check.origin().name(),"evidence",check==null?"":check.evidence()));
        }
        boolean scopeChanged=before!=null && (before.domain()!=after.domain() || !new HashSet<>(before.reportIds()).equals(new HashSet<>(after.reportIds())) || !Objects.equals(before.companyCode(),after.companyCode()));
        return Map.of("queryRef",reference,"queryFingerprint",fingerprint(after,columns),"conditions",evidence,"added",added,"removed",removed,"retained",retained,
                "scopeChanged",scopeChanged,"groupingChanged",before!=null && old.keySet().equals(current.keySet()) && !groups(before,columns).equals(groups(after,columns)),
                "previousConditions",before==null?List.of():before.conditions(),"currentConditions",after.conditions());
    }
    /** 明确表达一个字段谓词，供用户编辑后的本轮输入和界面解释采用同一运算含义。 */
    public static String text(BusinessQuery.Filter filter){String op=switch(filter.operator()){
        case "EQ"->"等于";case "NE"->"不等于";case "GT"->"大于";case "GTE"->"大于等于";case "LT"->"小于";case "LTE"->"小于等于";
        case "IN"->"属于集合";case "NOT_IN"->"不属于集合";case "IS_NULL"->"为空";case "NOT_NULL"->"非空";case "CONTAINS"->"包含";case "STARTS_WITH"->"开头为";default->throw new ApiException(422,"查询比较符无效");};
        return filter.field()+" "+op+(filter.values().isEmpty()?"":" "+filter.values().stream().map(JsonUtil::toJson).collect(java.util.stream.Collectors.joining("、")));}
    static Map<String,BusinessQuery.Filter> atoms(BusinessQuery query,List<FieldInfo> columns){var result=new TreeMap<String,BusinessQuery.Filter>();query.conditions().forEach(g->g.allOf().forEach(c->result.put(atom(c,columns),c)));return result;}
    private static List<List<String>> groups(BusinessQuery query,List<FieldInfo> columns){return query.conditions().stream().map(g->g.allOf().stream().map(c->atom(c,columns)).distinct().sorted().toList()).distinct().sorted(Comparator.comparing(JsonUtil::toJson)).toList();}
    private static String atom(BusinessQuery.Filter filter,List<FieldInfo> columns){
        String type=columns.stream().filter(f->f.name().equals(filter.field())).map(FieldInfo::type).findFirst().orElse("string");
        var values=filter.values().stream().map(v->{var value=FieldFact.scalar(type,v);return value instanceof BigDecimal decimal?decimal.stripTrailingZeros().toPlainString():value.toString();}).distinct().sorted().toList();
        return JsonUtil.toJson(List.of(filter.field(),filter.operator(),values));
    }
}
