package com.example.report.assistant;

import com.example.report.common.JsonUtil;
import java.util.*;

/** 统一业务助手的原生 JSON Schema；路由和业务查询共享严格结构，模型不能传入身份、SQL 或执行接口。 */
public final class AssistantSchema {
    private AssistantSchema() { }
    /** 生成供模型与 HTTP MCP 共用的查询契约；可空字段仍须显式输出。 */
    public static Map<String,Object> querySchema() {
        var filter=object(props("field",string(false),"operator",enumeration("EQ","NE","IN","NOT_IN","IS_NULL","NOT_NULL","CONTAINS","STARTS_WITH","GT","GTE","LT","LTE"),
                "values",array(string(false),30)));
        return object(props("domain",enumeration("REPORT","DISPATCH","WORK_ORDER"),"view",enumeration("LIST","DETAIL","SUMMARY"),
                "reportIds",array(string(false),100),"companyCode",string(true),"conditions",array(object(props("allOf",array(filter,8))),8),
                "sortField",string(true),"descending",Map.of("type","boolean"),"page",Map.of("type","integer","minimum",1,"maximum",2000),
                "size",Map.of("type","integer","minimum",1,"maximum",50),"groupBy",string(true)));
    }
    /** 模型一次选择业务查询或已有派单规划；澄清只输出说明，不携带可执行查询。 */
    public static String planSchema() {
        var nullableQuery=new LinkedHashMap<>(querySchema());nullableQuery.put("type",List.of("object","null"));
        return JsonUtil.toJson(object(props("route",enumeration("BUSINESS_QUERY","DISPATCH","HELP","CLARIFY"),"query",nullableQuery,
                "followUp",Map.of("type","boolean"),"clarification",string(true))));
    }
    private static Map<String,Object> object(Map<String,Object> fields){return props("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false);}
    private static Map<String,Object> string(boolean nullable){return props("type",nullable?List.of("string","null"):"string","maxLength",512);}
    private static Map<String,Object> enumeration(String... values){return props("type","string","enum",List.of(values));}
    private static Map<String,Object> array(Map<String,Object> items,int max){return props("type","array","items",items,"maxItems",max);}
    private static Map<String,Object> props(Object... entries){var result=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)result.put(entries[i].toString(),entries[i+1]);return result;}
}
