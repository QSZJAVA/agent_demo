package com.example.report.assistant;

import com.example.report.common.JsonUtil;
import java.util.*;

/** 统一业务助手的原生 JSON Schema；路由和业务查询共享严格结构，模型不能传入身份、SQL 或执行接口。 */
public final class AssistantSchema {
    private AssistantSchema() { }
    /** 生成供模型与 HTTP MCP 共用的查询契约；可空字段仍须显式输出。 */
    public static Map<String,Object> querySchema() {
        var operator=enumeration("EQ","NE","IN","NOT_IN","IS_NULL","NOT_NULL","CONTAINS","STARTS_WITH","GT","GTE","LT","LTE");
        operator.put("description","完整产品、类别、客户、编号等值默认精确EQ；否定用NE/NOT_IN。只有本轮明确要求部分包含或前缀匹配时才使用CONTAINS/STARTS_WITH，不能因当前结果相同放宽条件。");
        var filter=object(props("field",string(false),"operator",operator,
                "values",array(string(false),30)));
        var conjunction=array(filter,8);conjunction.put("description","必须同时满足的全部条件。区间的上下界、本人归属和状态等并列限制放在同一个allOf数组中。");
        conjunction.put("minItems",1);
        var conditions=array(object(props("allOf",conjunction)),8);
        conditions.put("description","无筛选条件时必须是空数组[]，不能创建空的allOf。存在多个同时限制的条件时只创建一个非空组，多个条件放入该组allOf。组之间是逻辑OR，只有明确要求替代集合时才创建多个组。");
        var view=enumeration("LIST","DETAIL","SUMMARY","ELIGIBILITY");
        view.put("description","ELIGIBILITY仅用于询问单条报表记录当前是否符合派单条件，以一张报表和准确recordId/docNo定位；不会生成候选或清单。");
        return object(props("domain",enumeration("REPORT","DISPATCH","WORK_ORDER"),"view",view,
                "reportIds",array(string(false),100),"companyCode",string(true),"conditions",conditions,
                "sortField",string(true),"descending",Map.of("type","boolean"),"page",Map.of("type","integer","minimum",1,"maximum",2000),
                "size",Map.of("type","integer","minimum",1,"maximum",50),"groupBy",string(true)));
    }
    /** 模型一次选择业务查询或已有派单规划；澄清只输出说明，不携带可执行查询。 */
    public static String planSchema() {
        var nullableQuery=new LinkedHashMap<>(querySchema());nullableQuery.put("type",List.of("object","null"));
        var removals=array(object(props("field",string(false),"evidence",string(false))),16);
        removals.put("description","仅BUSINESS_QUERY且followUp=true时声明本轮撤销的旧查询筛选。DISPATCH、HELP、CLARIFY以及独立新查询必须为空数组；派单候选的排除/恢复不是这里的筛选撤销。");
        Map<String,Object> intent;
        try(var in=new org.springframework.core.io.ClassPathResource("semantic/intent-v1.schema.json").getInputStream()) {
            intent=JsonUtil.toMap(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        } catch(java.io.IOException error) {throw new IllegalStateException("派单任务协议无法读取",error);}
        var rowKey=string(false);rowKey.put("pattern","^row-[1-9][0-9]{0,3}$");
        var references=array(rowKey,50);references.put("description","仅QUERY_ROWS使用queryObjects.rows中的row-N键；所有其他来源必须为空。候选ref_键放在intent.scopeChanges的mentions，不能填此处。");
        var dispatch=object(props("intent",intent,"source",enumeration("EXPLICIT_SCOPE","PREVIEW","PLAN","QUERY_ROWS","QUERY_ALL"),
                "sourceRef",string(true),"referenceKeys",references,"evidence",string(false)));
        dispatch.put("type",List.of("object","null"));
        return JsonUtil.toJson(object(props("route",enumeration("BUSINESS_QUERY","DISPATCH","HELP","CLARIFY"),"query",nullableQuery,
                "followUp",Map.of("type","boolean"),"clarification",string(true),"removedFilters",removals,"dispatch",dispatch)));
    }
    /** 复核先声明原文语义，再给完整期望；通过与差异由程序计算，不接受自由文本自报结论。 */
    public static String reviewSchema() {
        var requirements=array(object(props("aspect",enumeration("ACTION","SCOPE","CONDITIONS","REFERENCE","PRESENTATION","CONTINUITY","CAPABILITY"),
                "messageIndex",Map.of("type","integer","minimum",-1,"maximum",15),"evidence",string(false),"meaning",string(false))),16);requirements.put("minItems",1);
        var count=object(props("count",Map.of("type","integer","minimum",0,"maximum",100000),"evidence",string(false)));
        count.put("type",List.of("object","null"));
        count.put("description","仅当本轮明确指定最终候选选择/待确认清单总条数时填写；金额、序号、排除或恢复操作的局部条数不是最终总数。未限定时为null。");
        return JsonUtil.toJson(object(props("requirements",requirements,"expectedPlan",JsonUtil.toMap(planSchema()),"targetCount",count)));
    }
    private static Map<String,Object> object(Map<String,Object> fields){return props("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false);}
    private static Map<String,Object> string(boolean nullable){return props("type",nullable?List.of("string","null"):"string","maxLength",512);}
    private static Map<String,Object> enumeration(String... values){return props("type","string","enum",List.of(values));}
    private static Map<String,Object> array(Map<String,Object> items,int max){return props("type","array","items",items,"maxItems",max);}
    private static Map<String,Object> props(Object... entries){var result=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)result.put(entries[i].toString(),entries[i+1]);return result;}
}
