package com.example.report.assistant;

import com.example.report.common.JsonUtil;
import java.util.*;

/** 统一业务助手的原生 JSON Schema；路由和业务查询共享严格结构，模型不能传入身份、SQL 或执行接口。 */
public final class AssistantSchema {
    private AssistantSchema() { }
    /** 生成供模型与 HTTP MCP 共用的查询契约；可空字段仍须显式输出。 */
    public static Map<String,Object> querySchema() {
        var filter=filterSchema();
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
    /** 查询和条件证据共享同一原子结构，避免额外的数组下标或并行定义导致证据错位。 */
    private static Map<String,Object> filterSchema() {
        var operator=enumeration("EQ","NE","IN","NOT_IN","IS_NULL","NOT_NULL","CONTAINS","STARTS_WITH","GT","GTE","LT","LTE");
        // 比较边界放在原生Schema的操作符定义处，避免模型在长目录上下文中仅记住自由文本的近义改写。
        operator.put("description","EQ为等于，NE为不等于。GTE表示x>=阈值（以上、至少、不少于、不低于、at least），LTE表示x<=阈值（以下、至多、不超过、不高于、at most），两者均包含恰好等于；GT表示x>阈值（大于、超过），LT表示x<阈值（小于、少于），两者均排除等于。先核对等于阈值是否满足原文，再选操作符，不能将以上改写成超过。完整产品、类别、客户、编号等值默认精确EQ；否定用NE/NOT_IN。明确部分包含或前缀匹配才用CONTAINS/STARTS_WITH，不因当前结果相同放宽条件。");
        return object(props("field",string(false),"operator",operator,
                "values",array(string(false),30)));
    }
    /** 模型一次选择业务查询或已有派单规划；澄清只输出说明，不携带可执行查询。 */
    public static String planSchema() {
        var nullableQuery=new LinkedHashMap<>(querySchema());nullableQuery.put("type",List.of("object","null"));
        var removals=array(object(props("field",string(false),"evidence",string(false))),16);
        removals.put("description","仅BUSINESS_QUERY且followUp=true时声明整个旧字段限制被撤销，且该字段不再出现在新conditions。更换比较符、阈值或一个端点后仍使用该字段时不填。DISPATCH、HELP、CLARIFY及独立新查询必须为空；候选排除/恢复不是筛选撤销。");
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
                "messageIndex",Map.of("type","integer","enum",List.of(-1)),"evidence",string(false),"meaning",string(false))),16);requirements.put("minItems",1);
        requirements.put("description","每项都是本轮要求，messageIndex固定-1、evidence逐字引用当前message。历史只用于meaning解释指代或承接，不能代替本轮改变条件的依据。记录恢复/排除也须有本轮CONDITIONS依据。");
        var count=targetCountSchema();
        var checks=array(object(props("condition",filterSchema(),"origin",enumeration("CURRENT_REQUEST","ACTIVE_QUERY","VISIBLE_OBJECT"),
                "evidence",string(false),"negationEvidence",string(false))),128);
        checks.put("description","每项严格只有condition、origin、evidence、negationEvidence。condition按field、operator、values绑定expectedPlan内实际字段条件，与数组顺序无关；同一条件重复出现时只说明一次。CURRENT_REQUEST是本轮新增/修改；ACTIVE_QUERY只能继承previousQuery中完全相同且仍有效的条件；VISIBLE_OBJECT只能引用实际展示字段值。evidence为本轮原话。negationEvidence只引用对整个比较取反的独立否定短语；不含本数及候选排除时为空。无条件时为[]。");
        var changes=array(object(props("condition",filterSchema(),"evidence",string(false))),128);
        changes.put("description","每项严格只有condition和evidence。同报表、公司和数据域的FOLLOW_UP中，每个未原样保留的previousQuery旧条件须逐项说明；condition复制真实旧条件，evidence引用本轮授权改变它的原话。端点方向由旧operator确定，不额外输出scope。只改上限不删除下限，反之亦然。重复相同限制没有旧条件变化。没有变化、独立查询、跨范围查询、资格核验及非查询均为[]。");
        // 先生成实际期望计划，再为其中存在的条件声明证据；避免先列历史条件后才决定澄清而留下无效证据。
        return JsonUtil.toJson(object(props("requirements",requirements,"expectedPlan",JsonUtil.toMap(planSchema()),"conditionChecks",checks,"priorConditionChanges",changes,"targetCount",count)));
    }
    /** 详细复核前使用的独立目标契约，不暴露记录选择、SQL或执行参数。 */
    public static String purposeSchema(){
        var purpose=enumeration(Arrays.stream(TaskPurpose.Purpose.values()).map(Enum::name).toArray(String[]::new));
        purpose.put("description","按用户要求的结果分类。询问能力或是否支持某项操作使用HELP，即使该操作当前不支持；只有明确请求执行未支持操作或目标多义才CLARIFY，不能把解释能力当成执行授权。");
        var domain=props("type",List.of("string","null"),"enum",Arrays.asList("REPORT","DISPATCH","WORK_ORDER",null),
                "description","BUSINESS_QUERY必须填写REPORT报表业务记录、DISPATCH派单记录或WORK_ORDER工单；其他目标填null。FOLLOW_UP必须与activeQuery.domain相同，跨域查询必须INDEPENDENT。");
        var result=string(false);result.put("description","先用一句业务语言说明用户本轮想得到的结果，包括明确总数与限定；这是目标，不是系统要先做的步骤。需要先核对候选时也不能把用户的建单目标改成预览。");
        var view=props("type",List.of("string","null"),"enum",Arrays.asList("LIST","DETAIL","SUMMARY","ELIGIBILITY",null),
                "description","BUSINESS_QUERY必须区分集合列表LIST、指定单条详情DETAIL、集合统计SUMMARY和单条规则资格ELIGIBILITY；其他目标填null。展开已展示且唯一定位的单据使用DETAIL，不能因列表也返回一条而替换展示目标。");
        return JsonUtil.toJson(object(props("requestedResult",result,"purpose",purpose,
            "queryDomain",domain,"queryView",view,"queryContext",enumeration(Arrays.stream(TaskPurpose.QueryContext.values()).map(Enum::name).toArray(String[]::new)),"evidence",string(false),"targetCount",targetCountSchema())));
    }
    /** 轻量目标与完整复核共享最终数量结构；实际条数另由同一只读预检核对。 */
    private static Map<String,Object> targetCountSchema() {
        var count=object(props("count",Map.of("type","integer","minimum",0,"maximum",100000),"evidence",string(false)));
        count.put("type",List.of("object","null"));
        count.put("description","仅当本轮明确指定最终候选选择/待确认清单总条数时填写；金额、序号、排除或恢复操作的局部条数不是最终总数。未限定时必须为null。count不依据当前实际条数推断。");
        return count;
    }
    private static Map<String,Object> object(Map<String,Object> fields){return props("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false);}
    private static Map<String,Object> string(boolean nullable){return props("type",nullable?List.of("string","null"):"string","maxLength",512);}
    private static Map<String,Object> enumeration(String... values){return props("type","string","enum",List.of(values));}
    private static Map<String,Object> array(Map<String,Object> items,int max){return props("type","array","items",items,"maxItems",max);}
    private static Map<String,Object> props(Object... entries){var result=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)result.put(entries[i].toString(),entries[i+1]);return result;}
}
