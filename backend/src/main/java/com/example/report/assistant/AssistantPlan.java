package com.example.report.assistant;

import com.example.report.common.ApiException;

/**
 * 统一助手的本轮计划；查询、派单、知识与有限分析分别绑定完整指令，不能借只读分支夹带写入或扩大来源。
 * @param route BUSINESS_QUERY业务查询、DISPATCH派单规划、KNOWLEDGE知识问答、ANALYSIS有限分析、HELP帮助或CLARIFY澄清
 * @param query 完整查询，仅 BUSINESS_QUERY 时非空
 * @param followUp 仅业务查询分支用于声明依赖上次成功查询或展示顺序；派单分支使用其独立状态与校验，不据此读取查询上下文
 * @param clarification 澄清说明，仅用于告诉用户缺少什么，不作为执行条件
 * @param removedFilters 本轮追问明确撤销的旧筛选字段及原文证据；空集合不得静默删除旧字段限制
 * @param dispatch 完整派单动作及对象绑定，仅DISPATCH路由非空；不包含确认执行能力
 * @param clarificationOptions 有目录依据的澄清候选，仅CLARIFY可提供；无法枚举时为空
 * @param analysis 知识或有限分析的单范围读取指令，仅KNOWLEDGE/ANALYSIS非空；本轮原问题由外层服务绑定
 */
public record AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification,java.util.List<FilterRemoval> removedFilters,DispatchDirective dispatch,ClarificationOptions clarificationOptions,AnalysisDirective analysis) {
    /** 每条路由只进入自己的受限执行契约，不包含派单确认或其他业务写入能力。 */
    public enum Route { BUSINESS_QUERY, DISPATCH, KNOWLEDGE, ANALYSIS, HELP, CLARIFY }
    public AssistantPlan {
        if(route==null || (route==Route.BUSINESS_QUERY)!=(query!=null) || (clarification!=null && clarification.length()>1000))
            throw new ApiException(422,"助手计划格式不完整");
        if((route==Route.DISPATCH)!=(dispatch!=null))throw new ApiException(422,"派单路由必须同时提供完整动作和目标，其余路由dispatch必须为空");
        if((route==Route.KNOWLEDGE||route==Route.ANALYSIS)!=(analysis!=null))throw new ApiException(422,"知识和分析路由必须提供完整analysis指令，其余路由analysis必须为空");
        if(analysis!=null&&((route==Route.KNOWLEDGE)!=(analysis.mode()==com.example.report.analysis.AnalysisContract.Mode.KNOWLEDGE_ONLY)))
            throw new ApiException(422,"知识路由只允许知识问答模式，分析路由必须明确当前数据或选定快照模式");
        if(route!=Route.BUSINESS_QUERY && followUp)throw new ApiException(422,"followUp仅用于业务查询，派单对象由source及sourceRef绑定");
        if(route!=Route.CLARIFY && clarification!=null)throw new ApiException(422,"只有CLARIFY路由可以携带澄清说明");
        if(route!=Route.CLARIFY && clarificationOptions!=null)throw new ApiException(422,"只有CLARIFY路由可以携带澄清候选");
        if(route==Route.CLARIFY && (clarification==null || clarification.isBlank())) throw new ApiException(422,"请明确需要查询的业务对象");
        if(removedFilters==null || removedFilters.size()>16)throw new ApiException(422,"removedFilters必须显式提供数组且最多16项");
        if((!followUp || route!=Route.BUSINESS_QUERY) && !removedFilters.isEmpty())throw new ApiException(422,
                "当前route="+route+"、followUp="+followUp+"，removedFilters必须为[]。独立查询直接用新conditions表达本轮范围，不声明旧字段撤销；不能为了保留removedFilters把独立查询改成followUp=true");
        removedFilters=java.util.List.copyOf(removedFilters);
        for(var removal:removedFilters)if(removal.field()==null || !removal.field().matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || removal.evidence()==null || removal.evidence().isBlank() || removal.evidence().length()>1000)
            throw new ApiException(422,"筛选撤销缺少字段或当前原文证据");
    }
    /** 程序构造不含知识或分析的计划；模型JSON仍须显式提供analysis字段，不能据此兼容缺字段的外部协议。 */
    public AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification,java.util.List<FilterRemoval> removals,DispatchDirective dispatch,ClarificationOptions options){this(route,query,followUp,clarification,removals,dispatch,options,null);}
    /** 程序构造没有候选按钮的完整计划；模型仍须显式提供clarificationOptions。 */
    public AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification,java.util.List<FilterRemoval> removals,DispatchDirective dispatch){this(route,query,followUp,clarification,removals,dispatch,null);}
    /** 服务端无需撤销筛选时的完整计划构造器；模型JSON仍须显式提供全部字段。 */
    public AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification) {this(route,query,followUp,clarification,java.util.List.of(),null);}
    /** 服务端构造包含筛选撤销的完整只读计划；模型JSON仍显式输出dispatch字段。 */
    public AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification,java.util.List<FilterRemoval> removals) {this(route,query,followUp,clarification,removals,null);}
    /** 服务端构造完整派单计划；目标和动作一起传入，不从原文关键词补造。 */
    public static AssistantPlan dispatch(DispatchDirective directive) {return new AssistantPlan(Route.DISPATCH,null,false,null,java.util.List.of(),directive);}
    /** 程序只按已验证模式建立知识或分析分支；实际问题和来源解析由外层协调，不在计划中复制或推断。 */
    public static AssistantPlan analysis(AnalysisDirective directive) {return new AssistantPlan(directive.mode()==com.example.report.analysis.AnalysisContract.Mode.KNOWLEDGE_ONLY?Route.KNOWLEDGE:Route.ANALYSIS,null,false,null,java.util.List.of(),null,null,directive);}
    /**
     * 本轮明确撤销的筛选维度，不授权其他字段一同丢失。
     * @param field 上轮存在的筛选字段名称
     * @param evidence 用户本轮要求撤销该条件的连续原文，不得引用历史或模型解释
     */
    public record FilterRemoval(String field,String evidence) { }
}
