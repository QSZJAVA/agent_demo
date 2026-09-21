package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 消息：文本、工具调用、工具结果、结构化卡片都在这一张表
 */
@Data
@TableName("agent_message")
public class AgentMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL_CALL = "tool_call";
    public static final String ROLE_TOOL_RESULT = "tool_result";
    public static final String ROLE_CARD = "card";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private String userId;
    /** user / assistant / tool_call / tool_result / card */
    private String role;
    /** 文本内容；tool_call 时为工具参数 JSON；tool_result 时为返回摘要 */
    private String content;
    /** preview / plan / result，仅 role = card */
    private String cardType;
    /** 卡片载荷 JSON */
    private String payload;
    private String toolName;
    private String previewId;
    private String planId;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latencyMs;
    private LocalDateTime createdAt;
}
