package com.example.report.assistant;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.ApiException;
import com.example.report.rule.FieldFact;
import com.example.report.semantic.FieldSelection;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;

/** 有界完整事实集上的确定性筛选、排序、分页及总结；数据提供者先完成权限过滤，模型不参与统计。 */
public final class BusinessQueryEngine {
    private BusinessQueryEngine() { }
    /**
     * 全部条件先按字段白名单校验，再计算完整匹配集；无结果不意味着字段合法，验证不能依赖是否存在记录。
     * @param query 经协议校验的查询
     * @param columns 数据域字段元数据，名称不得重复
     * @param input 数据提供者确认完整且已授权的事实，稳定 rowKey 必須唯一
     * @param source 可追溯来源说明
     * @return 该批事实的精确总数、统计和当前页，无法处理时明确拒绝
     */
    public static BusinessResult execute(BusinessQuery query,List<FieldInfo> columns,List<Map<String,Object>> input,String source) {
        var fields=new LinkedHashMap<String,FieldInfo>();
        for(var field:columns) if(fields.put(field.name(),field)!=null) throw new ApiException(502,"查询字段定义重复");
        var predicate=compile(query,fields);
        if(query.sortField()!=null) require(fields,query.sortField());
        if(query.groupBy()!=null) require(fields,query.groupBy());
        if(input.stream().anyMatch(row->row.get("rowKey")==null) || input.stream().map(row->row.get("rowKey")).distinct().count()!=input.size())
            throw new ApiException(502,"业务数据标识不完整或重复，查询未完成");
        List<Map<String,Object>> matched=input.stream().filter(predicate).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Comparator<Map<String,Object>> comparator=Comparator.comparing(row->row.get("rowKey").toString());
        if(query.sortField()!=null) {
            var field=fields.get(query.sortField());
            Comparator<Map<String,Object>> order=(a,b)->{
                String left=text(a.get(field.name())),right=text(b.get(field.name()));
                // 缺失值不是最大业务值，升降序都放到末尾；只反转非空值之间的比较。
                if(left==null || right==null)return left==right?0:left==null?1:-1;
                int orderValue=compareNullable(field.type(),left,right);return query.descending()?-orderValue:orderValue;
            };
            comparator=order.thenComparing(comparator);
        }
        matched.sort(comparator);
        var amounts=new TreeMap<String,BigDecimal>();var statuses=new TreeMap<String,Integer>();
        var groups=new TreeMap<String,Integer>();var approvers=new TreeMap<String,Integer>();
        int unclassified=0;
        for(var row:matched) {
            String currency=Objects.toString(row.get("currency"),"");
            if(row.get("amount")!=null) {
                if(currency.isBlank())unclassified++;
                else amounts.merge(currency,new BigDecimal(row.get("amount").toString()),BigDecimal::add);
            }
            statuses.merge(Objects.toString(row.get("status"),"未提供"),1,Integer::sum);
            if(query.groupBy()!=null) groups.merge(Objects.toString(row.get(query.groupBy()),"未提供"),1,Integer::sum);
            if("待审批".equals(row.get("status")) && row.get("assignee")!=null) approvers.merge(row.get("assignee").toString(),1,Integer::sum);
        }
        var exactAmounts=new TreeMap<String,String>();amounts.forEach((currency,value)->exactAmounts.put(currency,value.toPlainString()));
        if((query.view()==BusinessQuery.View.DETAIL || query.view()==BusinessQuery.View.ELIGIBILITY) && matched.size()!=1)
            throw new ApiException(422,matched.isEmpty()?"未找到匹配记录或无权查看，请核对编号":"匹配到多条记录，请指定准确编号后查看详情");
        int start=Math.min(matched.size(),Math.multiplyExact(query.page()-1,query.size()));
        var page=List.copyOf(matched.subList(start,Math.min(matched.size(),start+query.size())));
        return new BusinessResult(query,OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(),source,List.copyOf(columns),page,matched.size(),
                new BusinessResult.Summary(matched.size(),exactAmounts,statuses,groups,approvers,unclassified));
    }
    /** 将字段条件编译为纯读取谓词；NULL 不满足普通比较，空值判断须明确声明。 */
    private static Predicate<Map<String,Object>> compile(BusinessQuery query,Map<String,FieldInfo> fields) {
        List<List<Predicate<Map<String,Object>>>> groups=new ArrayList<>();
        for(var group:query.conditions()) {
            List<Predicate<Map<String,Object>>> tests=new ArrayList<>();
            for(var condition:group.allOf()) {
                var field=require(fields,condition.field());
                if(!FieldSelection.operators(field.type()).contains(condition.operator())) throw new ApiException(422,"字段不支持该比较："+field.name());
                condition.values().forEach(v->FieldFact.scalar(field.type(),v));
                tests.add(row->test(text(row.get(field.name())),field.type(),condition));
            }
            groups.add(tests);
        }
        return row->groups.isEmpty() || groups.stream().anyMatch(group->group.stream().allMatch(test->test.test(row)));
    }
    private static FieldInfo require(Map<String,FieldInfo> fields,String key) {
        var field=fields.get(key);if(field==null || !FieldFact.supported(field.type())) throw new ApiException(422,"查询字段未配置："+key);return field;
    }
    private static boolean test(String value,String type,BusinessQuery.Filter c) {
        if("IS_NULL".equals(c.operator())) return value==null;
        if("NOT_NULL".equals(c.operator())) return value!=null;
        if(value==null) return false;
        if("IN".equals(c.operator()) || "NOT_IN".equals(c.operator())) {
            boolean contains=c.values().stream().anyMatch(v->compareNullable(type,value,v)==0);return "IN".equals(c.operator())?contains:!contains;
        }
        String right=c.values().get(0);
        if("CONTAINS".equals(c.operator())) return value.contains(right);
        if("STARTS_WITH".equals(c.operator())) return value.startsWith(right);
        int n=compareNullable(type,value,right);
        return switch(c.operator()){case "EQ"->n==0;case "NE"->n!=0;case "GT"->n>0;case "GTE"->n>=0;case "LT"->n<0;case "LTE"->n<=0;default->throw new ApiException(422,"不支持的比较操作");};
    }
    private static int compareNullable(String type,String a,String b) {
        if(a==null || b==null) return a==b?0:a==null?1:-1;
        if(Set.of("decimal","integer","long").contains(type)) return new BigDecimal(a).compareTo(new BigDecimal(b));
        return a.compareTo(b);
    }
    private static String text(Object value){return value==null?null:value instanceof BigDecimal d?d.toPlainString():value.toString();}
}
