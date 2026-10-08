package com.example.report.assistant;

import com.example.report.common.ApiException;

/**
 * 统一助手的本轮计划；业务查询和派单规划互斥，不能借助只读查询夹带写入。
 * @param route 业务查询、派单规划、帮助或澄清
 * @param query 完整查询，仅 BUSINESS_QUERY 时非空
 * @param followUp 仅业务查询分支用于声明依赖上次成功查询或展示顺序；派单分支使用其独立状态与校验，不据此读取查询上下文
 * @param clarification 澄清说明，仅用于告诉用户缺少什么，不作为执行条件
 * @param removedFilters 本轮追问明确撤销的旧筛选字段及原文证据；空集合不得静默删除旧字段限制
 * @param dispatch 完整派单动作及对象绑定，仅DISPATCH路由非空；不包含确认执行能力
 */
public record AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification,java.util.List<FilterRemoval> removedFilters,DispatchDirective dispatch) {
    public enum Route { BUSINESS_QUERY, DISPATCH, HELP, CLARIFY }
    public AssistantPlan {
        if(route==null || (route==Route.BUSINESS_QUERY)!=(query!=null) || (clarification!=null && clarification.length()>1000))
            throw new ApiException(422,"助手计划格式不完整");
        if((route==Route.DISPATCH)!=(dispatch!=null))throw new ApiException(422,"派单路由必须同时提供完整动作和目标，其余路由dispatch必须为空");
        if(route!=Route.BUSINESS_QUERY && followUp)throw new ApiException(422,"followUp仅用于业务查询，派单对象由source及sourceRef绑定");
        if(route!=Route.CLARIFY && clarification!=null)throw new ApiException(422,"只有CLARIFY路由可以携带澄清说明");
        if(route==Route.CLARIFY && (clarification==null || clarification.isBlank())) throw new ApiException(422,"请明确需要查询的业务对象");
        if(removedFilters==null || removedFilters.size()>16 || ((!followUp || route!=Route.BUSINESS_QUERY) && !removedFilters.isEmpty()))throw new ApiException(422,"筛选撤销仅用于业务追问");
        removedFilters=java.util.List.copyOf(removedFilters);
        for(var removal:removedFilters)if(removal.field()==null || !removal.field().matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || removal.evidence()==null || removal.evidence().isBlank() || removal.evidence().length()>1000)
            throw new ApiException(422,"筛选撤销缺少字段或当前原文证据");
    }
    /** 服务端无需撤销筛选时的完整计划构造器；模型JSON仍须显式提供全部字段。 */
    public AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification) {this(route,query,followUp,clarification,java.util.List.of(),null);}
    /** 服务端构造包含筛选撤销的完整只读计划；模型JSON仍显式输出dispatch字段。 */
    public AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification,java.util.List<FilterRemoval> removals) {this(route,query,followUp,clarification,removals,null);}
    /** 服务端构造完整派单计划；目标和动作一起传入，不从原文关键词补造。 */
    public static AssistantPlan dispatch(DispatchDirective directive) {return new AssistantPlan(Route.DISPATCH,null,false,null,java.util.List.of(),directive);}
    /**
     * 本轮明确撤销的筛选维度，不授权其他字段一同丢失。
     * @param field 上轮存在的筛选字段名称
     * @param evidence 用户本轮要求撤销该条件的连续原文，不得引用历史或模型解释
     */
    public record FilterRemoval(String field,String evidence) { }
}
