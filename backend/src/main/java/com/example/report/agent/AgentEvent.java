package com.example.report.agent;

/**
 * 对话 SSE 事件协议；事件数据由服务端业务结果生成，前端按 type 分流渲染。
 * @param type 事件类型，例如文本增量、预览、清单、错误或完成
 * @param data 该类型的结构化载荷，发送前须执行脱敏
 */
public record AgentEvent(String type, Object data) {

    public static final String TEXT = "text";
    public static final String CONVERSATION = "conversation";
    public static final String PREVIEW = "preview";
    public static final String PREVIEW_JOB = "preview_job";
    /** 报表选择卡片：一句话命中多张报表，需要用户选择 */
    public static final String CHOICE = "choice";
    public static final String PLAN = "plan";
    public static final String RESULT = "result";
    public static final String ERROR = "error";
    public static final String DONE = "done";
}
