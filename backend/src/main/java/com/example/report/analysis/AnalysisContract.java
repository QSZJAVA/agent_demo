package com.example.report.analysis;

import com.example.report.assistant.*;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import com.example.report.operations.ModelEgressPolicy;
import com.example.report.workspace.QueryAnalysis;
import java.util.*;

/** 有限分析图的唯一外部契约；模型只能组合白名单只读操作，不能提供身份、SQL、执行接口或任意引用。 */
public final class AnalysisContract {
    public static final int MAX_STEPS=6, MAX_MODEL_CALLS=8, MAX_QUERIES=2;
    /** 规划与独立复核共用的操作语义；source/other 始终指向步骤，而不是字段或知识来源编号。 */
    static final String OPERATIONS="""
        步骤引用从0开始，必须指向前序步骤。各操作的必填输入如下，其余参数必须显式为null：
        QUERY：query非空；source=null、other=null。query为REPORT/LIST，保持scope公司和报表，page=1、size=20、groupBy=null。page/size只控制首屏展示，服务端始终保存全部匹配事实为完整快照，后续GROUP/RANK/COMPARE读取该全集；不能误作只读20行或改成SUMMARY。
        SNAPSHOT：source=null、other=null，无额外参数；只读取界面明确给定的snapshotId，没有snapshotId时不可用。
        GROUP：source为QUERY/SNAPSHOT/DRILLDOWN步骤序号，other=null，analysis非空。dimensions最多3个真实字段；bucket=NONE时dateField=null，否则为真实日期字段；compareMonth只在MONTH时可用。
        RANK：source为QUERY/SNAPSHOT/DRILLDOWN/GROUP步骤序号，other=null，rank非空。行排名field为真实字段；GROUP排名field只能是sum、count或average，不能写amount或sum(amount)。count为用户明确数量1至50。
        DRILLDOWN：source为原QUERY/SNAPSHOT/DRILLDOWN行集合步骤序号，other为该行集合经GROUP后得到的RANK步骤序号；无其他参数。展开同一快照中入选分组的全部明细，不重新查询。
        展开引用等式：plan.steps[plan.steps[other].source].source == source。source不是GROUP序号；other先指向RANK，再回溯GROUP，最后回到source行集合。
        COMPARE：source和other分别为两个不同QUERY/SNAPSHOT步骤序号；无其他参数；方向为source（之前）对other（之后），不跨源关联。
        KNOWLEDGE：knowledgeQuestion为非空检索问题；source=null、other=null。按已选scope自行检索当前授权且生效的目录、词典、规则和已发布制度，不需要用户提供来源编号，也不依赖QUERY。
        分组金额统计固定使用amount和currency，按币种分别计算；金额排名由程序根据实际完整结果检查唯一币种，缺值或跨币种时明确失败，不自动换算或忽略。
        """;
    private AnalysisContract() { }

    /**
     * 用户明确选定的单一业务范围，不从知识正文扩大范围。
     * @param companyCode 当前有权访问的公司代码
     * @param reportId 当前有权访问的报表标识
     * @param question 本轮完整问题，1至2000字符；不自动继承历史文字
     * @param snapshotId 用户明确选择的本人查询快照，可空
     */
    public record Request(String companyCode,String reportId,String question,String snapshotId) {
        public Request {
            text(companyCode,32,"公司");text(reportId,64,"报表");text(question,2000,"问题");
            if(!reportId.matches("[a-z][a-z0-9-]{2,63}") || snapshotId!=null&&!snapshotId.matches("[a-f0-9]{32}"))fail("分析范围或快照标识无效");
            ModelEgressPolicy.requireSafeText(question);
        }
    }
    /**
     * 行排名使用来源字段；分组排名使用count、sum或average，均由程序计算。
     * @param field 行字段或分组指标，不接受表达式
     * @param descending 是否降序
     * @param count 明确的最终数量，1至50；不足时不静默少取
     */
    public record Rank(String field,boolean descending,int count) {
        public Rank {text(field,64,"排名字段");if(count<1||count>50)fail("排名数量须为1至50");}
    }
    /**
     * 一个有类型的步骤；所有不用的参数必须为null，引用为前序步骤的零起始序号。
     * @param action QUERY、SNAPSHOT、GROUP、RANK、DRILLDOWN、COMPARE或KNOWLEDGE
     * @param source 主输入步骤，基础查询和知识检索为空
     * @param other 对比的另一查询或展开使用的分组排名步骤，其余为空
     * @param query QUERY的完整查询定义，其余为空
     * @param analysis GROUP的确定性指标参数，其余为空
     * @param rank RANK的确定性排序参数，其余为空
     * @param knowledgeQuestion KNOWLEDGE的检索问题，其余为空
     */
    public record Step(String action,Integer source,Integer other,BusinessQuery query,QueryAnalysis.Request analysis,Rank rank,String knowledgeQuestion) {
        public Step {
            if(!Set.of("QUERY","SNAPSHOT","GROUP","RANK","DRILLDOWN","COMPARE","KNOWLEDGE").contains(Objects.toString(action,"")))fail("分析步骤不在只读白名单内");
            if(("QUERY".equals(action))!=(query!=null) || ("GROUP".equals(action))!=(analysis!=null)
                    || ("RANK".equals(action))!=(rank!=null) || ("KNOWLEDGE".equals(action))!=(knowledgeQuestion!=null))fail("步骤参数与操作不匹配");
            boolean root=Set.of("QUERY","SNAPSHOT","KNOWLEDGE").contains(action), paired=Set.of("DRILLDOWN","COMPARE").contains(action);
            if(root!=(source==null))fail(action+".source"+(root?"必须为null":"必须为前序输入步骤的零起始序号"));
            if(paired!=(other!=null))fail(action+".other"+(paired?"必须为前序配对步骤的零起始序号":"必须为null；主输入填写source"));
            if(knowledgeQuestion!=null)text(knowledgeQuestion,1000,"知识检索问题");
        }
    }
    /**
     * 模型规划结果；澄清分支不得携带可执行步骤。
     * @param mode EXECUTE或CLARIFY
     * @param objective 用户要求的业务结果说明
     * @param clarification 待用户明确的事项，仅CLARIFY非空
     * @param steps 至多六个有序步骤，前序引用自然构成无环图
     */
    public record Plan(String mode,String objective,String clarification,List<Step> steps) {
        public Plan {
            text(objective,500,"分析目标");
            if(!Set.of("EXECUTE","CLARIFY").contains(Objects.toString(mode,""))||steps==null||steps.size()>MAX_STEPS)fail("分析计划无效");
            steps=List.copyOf(steps);
            if("CLARIFY".equals(mode)){text(clarification,500,"澄清说明");if(!steps.isEmpty())fail("澄清不能附带执行步骤");}
            else if(steps.isEmpty()||clarification!=null)fail("执行计划必须有步骤且没有待澄清事项");
        }
    }
    /**
     * 独立模型复核只判断所给原始问题、事实与草稿，不执行工具或授予权限。
     * @param approved 是否完整满足原问题与事实依据
     * @param reason 核验理由或需要修正的问题，不作为业务事实
     */
    public record Review(boolean approved,String reason) {
        public Review {text(reason,1000,"复核说明");}
    }

    /** 在任何来源读取前验证完整图；所有基础查询必须落在用户明确选定的同一公司/报表内。 */
    public static void validate(Request request,Plan plan,List<FieldInfo> columns) {
        int bases=0;
        for(int i=0;i<plan.steps().size();i++) {
            var step=plan.steps().get(i);
            if(step.source()!=null&&(step.source()<0||step.source()>=i) || step.other()!=null&&(step.other()<0||step.other()>=i))fail("步骤只能引用已完成的前序步骤");
            switch(step.action()) {
                case "QUERY" -> {
                    bases++;var q=step.query();
                    if(q.domain()!=BusinessQuery.Domain.REPORT||q.view()!=BusinessQuery.View.LIST||q.page()!=1||q.size()!=20||q.groupBy()!=null
                            || !q.reportIds().equals(List.of(request.reportId()))||!Objects.equals(q.companyCode(),request.companyCode()))fail("基础查询必须保持选定公司、报表和完整列表语义");
                    BusinessQueryEngine.validate(q,columns);
                }
                case "SNAPSHOT" -> {bases++;if(request.snapshotId()==null)fail("没有用户明确选择的快照");}
                case "GROUP" -> {
                    rowsInput(plan,step.source());
                    for(String field:step.analysis().dimensions())field(columns,field);
                    if(step.analysis().dateField()!=null&& !"date".equals(field(columns,step.analysis().dateField()).type()))fail("趋势字段必须是目录日期字段");
                }
                case "RANK" -> {
                    String type=plan.steps().get(step.source()).action();
                    if("GROUP".equals(type)){if(!Set.of("count","sum","average").contains(step.rank().field()))fail("分组只能按计数、金额合计或均值排名");}
                    else {rowsInput(plan,step.source());field(columns,step.rank().field());}
                }
                case "DRILLDOWN" -> {
                    rowsInput(plan,step.source());var ranking=plan.steps().get(step.other());
                    if(!"RANK".equals(ranking.action()))fail("展开必须引用分组排名");var group=plan.steps().get(ranking.source());
                    if(!"GROUP".equals(group.action())||!Objects.equals(group.source(),step.source()))fail("展开分组必须来自同一份输入快照");
                }
                case "COMPARE" -> {
                    if(Objects.equals(step.source(),step.other()))fail("对比需要两个不同的基础查询");
                    baseInput(plan,step.source());baseInput(plan,step.other());
                }
                case "KNOWLEDGE" -> ModelEgressPolicy.requireSafeText(step.knowledgeQuestion());
                default -> fail("不支持的分析步骤");
            }
        }
        if(bases>MAX_QUERIES)fail("单次分析最多读取两份基础查询或快照");
    }
    /** 程序已验证的依赖路径供独立语义复核阅读；调用者必须先validate，不能用说明替代图校验。 */
    static List<Map<String,Object>> lineage(Plan plan) {
        var result=new ArrayList<Map<String,Object>>();
        for(int i=0;i<plan.steps().size();i++) {
            var step=plan.steps().get(i);if(!"DRILLDOWN".equals(step.action()))continue;
            int group=plan.steps().get(step.other()).source();
            result.add(Map.of("step",i,"rowsStep",step.source(),"groupStep",group,"rankStep",step.other(),
                    "meaning","从步骤"+step.source()+"的完整行集合中，展开步骤"+group+"分组后由步骤"+step.other()+"选中的分组对应全部明细"));
        }
        return result;
    }
    private static void baseInput(Plan plan,int index){if(!Set.of("QUERY","SNAPSHOT").contains(plan.steps().get(index).action()))fail("对比必须引用完整基础查询");}
    private static void rowsInput(Plan plan,int index){if(!Set.of("QUERY","SNAPSHOT","DRILLDOWN").contains(plan.steps().get(index).action()))fail("该步骤需要完整行集合输入");}
    private static FieldInfo field(List<FieldInfo> columns,String name){return columns.stream().filter(f->f.name().equals(name)).findFirst().orElseThrow(()->new ApiException(422,"分析字段不在当前目录内"));}

    /** 所有HTTP和模型输入共用严格解码，拒绝额外字段、缺字段、重复键和隐式类型转换。 */
    public static <T>T decode(Object input,Class<T> type) {
        try {String text=input instanceof String s?s:JsonUtil.toJson(input);if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128*1024)fail("分析参数超过体积预算");return JsonUtil.strictMapper().readValue(text,type);}
        catch(Exception failure){for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof ApiException api)throw api;
            // 诊断只记录异常类型和目标契约，不输出可能包含原问题、制度正文或凭据的解析器原始消息。
            org.slf4j.LoggerFactory.getLogger(AnalysisContract.class).warn("分析协议解码拒绝 target={} type={}",type.getSimpleName(),failure.getClass().getSimpleName());
            throw new ApiException(422,failure instanceof com.fasterxml.jackson.core.JsonParseException?"结构化JSON语法无效或含重复键；每个键只能出现一次":"分析参数的字段、类型或范围不符合当前协议");}
    }
    /** 原生Schema使用同一查询契约；不用的步骤参数仍要求显式为null。 */
    public static String planSchema() {
        var analysis=object(fields("dimensions",array(string(),3),"dateField",nullable(string()),"bucket",enumeration("NONE","DAY","MONTH"),"compareMonth",nullable(string())));
        var rank=object(fields("field",described(string(64),"行排名使用真实字段；GROUP排名只用sum/count/average，不使用amount或表达式"),"descending",Map.of("type","boolean"),"count",Map.of("type","integer","minimum",1,"maximum",50)));
        var reference=nullable(Map.of("type","integer","minimum",0,"maximum",MAX_STEPS-1));
        var step=object(fields("action",enumeration("QUERY","SNAPSHOT","GROUP","RANK","DRILLDOWN","COMPARE","KNOWLEDGE"),
                "source",described(reference,"GROUP/RANK/DRILLDOWN/COMPARE的前序主输入序号，从0开始；QUERY/SNAPSHOT/KNOWLEDGE必须为null"),
                "other",described(reference,"仅DRILLDOWN引用分组RANK序号，COMPARE引用另一个基础查询；其他操作必须为null"),
                "query",described(nullable(querySchema()),"仅QUERY非空；page/size只控制首屏，执行器保存并读取完整匹配集合"),"analysis",described(nullable(analysis),"仅GROUP非空"),
                "rank",described(nullable(rank),"仅RANK非空"),"knowledgeQuestion",described(nullable(string(1000)),"仅KNOWLEDGE非空；在已选scope检索，无需来源编号")));
        return JsonUtil.toJson(object(fields("mode",enumeration("EXECUTE","CLARIFY"),"objective",string(500),"clarification",nullable(string(500)),"steps",array(step,MAX_STEPS))));
    }
    public static String reviewSchema(){return JsonUtil.toJson(object(fields("approved",Map.of("type","boolean"),"reason",string(1000))));}
    /** 分析只使用完整REPORT列表语义，Schema同步收窄通用查询的域、视图及首屏参数，不能暴露不可执行分支。 */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> querySchema(){var schema=new LinkedHashMap<>(AssistantSchema.querySchema());var properties=new LinkedHashMap<>((Map<String,Object>)schema.get("properties"));
        properties.put("domain",enumeration("REPORT"));properties.put("view",enumeration("LIST"));properties.put("page",Map.of("type","integer","minimum",1,"maximum",1));
        properties.put("size",described(Map.of("type","integer","minimum",20,"maximum",20),"首屏展示20行；完整匹配集仍全部保存，不是查询总条数上限"));properties.put("groupBy",Map.of("type","null"));schema.put("properties",properties);return schema;}
    static Map<String,Object> fields(Object... values){var result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put(values[i].toString(),values[i+1]);return result;}
    static Map<String,Object> object(Map<String,Object> fields){return fields("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false);}
    static Map<String,Object> string(){return string(2000);}
    static Map<String,Object> string(int max){return fields("type","string","minLength",1,"maxLength",max);}
    static Map<String,Object> described(Map<String,Object> schema,String description){var result=new LinkedHashMap<>(schema);result.put("description",description);return result;}
    static Map<String,Object> enumeration(String... values){return fields("type","string","enum",List.of(values));}
    static Map<String,Object> array(Map<String,Object> item,int max){return fields("type","array","items",item,"maxItems",max);}
    static Map<String,Object> nullable(Map<String,Object> schema){var result=new LinkedHashMap<>(schema);result.put("type",List.of(schema.get("type"),"null"));return result;}
    static void text(String value,int max,String label){if(value==null||value.isBlank()||value.length()>max||value.indexOf('\0')>=0)fail(label+"为空或超出长度限制");}
    static void fail(String reason){throw new ApiException(422,reason);}
}
