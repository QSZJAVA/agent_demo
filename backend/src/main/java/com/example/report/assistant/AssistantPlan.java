package com.example.report.assistant;

import com.example.report.common.ApiException;

/**
 * 统一助手的本轮计划；业务查询和派单规划互斥，不能借助只读查询夹带写入。
 * @param route 业务查询、派单规划、帮助或澄清
 * @param query 完整查询，仅 BUSINESS_QUERY 时非空
 * @param followUp 仅业务查询分支用于声明依赖上次成功查询或展示顺序；派单分支使用其独立状态与校验，不据此读取查询上下文
 * @param clarification 澄清说明，仅用于告诉用户缺少什么，不作为执行条件
 */
public record AssistantPlan(Route route,BusinessQuery query,boolean followUp,String clarification) {
    public enum Route { BUSINESS_QUERY, DISPATCH, HELP, CLARIFY }
    public AssistantPlan {
        if(route==null || (route==Route.BUSINESS_QUERY)!=(query!=null) || (clarification!=null && clarification.length()>1000))
            throw new ApiException(422,"助手计划格式不完整");
        if(route==Route.CLARIFY && (clarification==null || clarification.isBlank())) throw new ApiException(422,"请明确需要查询的业务对象");
    }
}
