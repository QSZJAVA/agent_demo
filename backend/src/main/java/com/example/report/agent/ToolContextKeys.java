package com.example.report.agent;

import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

/**
 * ToolContext 里约定的键：身份与会话来自登录态，模型看不到也改不了
 */
public final class ToolContextKeys {

    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    /** 前端预览表格里取消勾选的单据号（等价于排除项） */
    public static final String UI_EXCLUDES = "uiExcludes";
    /** 取消勾选发生在哪张预览卡片上：只有与本次派单的预览一致时才生效 */
    public static final String UI_PREVIEW_ID = "uiPreviewId";
    /** 本轮用户是在当前查询范围上追加报表（例如"加上费用报表的"），预览需要与上一轮范围合并 */
    public static final String PREVIEW_APPEND = "previewAppend";
    /** 本轮用户是在当前查询范围上排除报表（例如"应收的也删掉"），预览需要从上一轮范围中减去 */
    public static final String PREVIEW_REMOVE = "previewRemove";
    /** 本轮请求的链路号：工具在响应式线程里执行，拿不到请求线程的 MDC */
    public static final String TRACE_ID = "traceId";

    private ToolContextKeys() {
    }

    public static String userId(ToolContext ctx) {
        return (String) ctx.getContext().get(USER_ID);
    }

    public static String conversationId(ToolContext ctx) {
        return (String) ctx.getContext().get(CONVERSATION_ID);
    }

    public static AgentEventChannel channel(ToolContext ctx) {
        Object c = ctx.getContext().get(AgentEventChannel.CONTEXT_KEY);
        return c instanceof AgentEventChannel ch ? ch : null;
    }

    @SuppressWarnings("unchecked")
    public static List<String> uiExcludes(ToolContext ctx) {
        Object v = ctx.getContext().get(UI_EXCLUDES);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    public static String uiPreviewId(ToolContext ctx) {
        Object v = ctx.getContext().get(UI_PREVIEW_ID);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    public static boolean previewAppend(ToolContext ctx) {
        return Boolean.TRUE.equals(ctx.getContext().get(PREVIEW_APPEND));
    }

    public static boolean previewRemove(ToolContext ctx) {
        return Boolean.TRUE.equals(ctx.getContext().get(PREVIEW_REMOVE));
    }

    public static String traceId(ToolContext ctx) {
        Object v = ctx.getContext().get(TRACE_ID);
        return v instanceof String s ? s : null;
    }
}
