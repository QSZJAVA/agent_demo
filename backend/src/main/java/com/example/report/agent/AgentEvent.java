package com.example.report.agent;

/**
 * 推给前端的 SSE 事件：type 即 SSE event 名，data 为 JSON 载荷
 */
public record AgentEvent(String type, Object data) {

    public static final String TEXT = "text";
    public static final String CONVERSATION = "conversation";
    public static final String PREVIEW = "preview";
    /** 报表选择卡片：一句话命中多张报表，需要用户选择 */
    public static final String CHOICE = "choice";
    public static final String PLAN = "plan";
    public static final String RESULT = "result";
    public static final String ERROR = "error";
    public static final String DONE = "done";
}
