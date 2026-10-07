package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.semantic.DialogueState;
import java.util.regex.Pattern;

/** 会话焦点下的路由一致性校验，只拒绝丢失选择操作的模型草稿，不据词语生成查询或替代模型解析。 */
public final class AssistantRouteGuard {
    private AssistantRouteGuard() { }
    // 这是选择操作的通用否决条件；新业务查询须有独立查询动作，不能把负向选择静默改成正向筛选。
    private static final Pattern SELECTION=Pattern.compile("不要(?!派单|生成|执行)|排除|取消勾选|取消选择|恢复|保留|只选|选中|不动|去掉|剔除|选上|不选|别选|\\b(?:exclude|deselect|restore|keep|select)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY=Pattern.compile("查询|查一下|查看|看一下|看看|列出|统计|汇总|总结|展示|浏览|检索|搜索|\\b(?:query|show|list|search|summarize)\\b",Pattern.CASE_INSENSITIVE);
    /** 仅在派单候选焦点存在、没有独立查询动作且原文包含选择操作时，禁止改走普通业务查询。 */
    public static void validate(String message,DialogueState state,AssistantPlan plan) {
        if("DISPATCH".equals(state.getAssistantFocus()) && state.getPreviewId()!=null && plan.route()==AssistantPlan.Route.BUSINESS_QUERY
                && SELECTION.matcher(message).find() && !QUERY.matcher(message).find())
            throw new ApiException(422,"本轮在已有派单候选焦点中调整选择，不能丢弃选择操作改为普通数据查询；请按原文重新判断完整路由");
    }
}
