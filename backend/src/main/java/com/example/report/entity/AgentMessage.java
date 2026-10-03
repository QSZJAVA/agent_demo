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

    /** 数据所属租户标识；查询和写入必须限定租户。 */
    private String tenantId;

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL_CALL = "tool_call";
    public static final String ROLE_TOOL_RESULT = "tool_result";
    public static final String ROLE_CARD = "card";

    /** 本表记录主键；数据库自增。*/
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联trace_event.id的证据标识；普通消息为空，唯一索引用于防止重复补写。 */
    private Long evidenceId;
    /** 请求链路标识，关联应用日志；允许为空，表示尚无该项数据。*/
    private String traceId;
    /** 关联 agent_conversation.id 的会话标识。 */
    private String conversationId;
    /** 记录所属用户标识，结合租户确定数据归属。*/
    private String userId;
    /** 消息角色：user、assistant、tool_call、tool_result、card。 */
    private String role;
    /** 文本或工具摘要；结构化卡片内容保存于payload；允许为空，表示尚无该项数据。*/
    private String content;
    /** 卡片类型：preview、plan、result；非卡片消息为空。 */
    private String cardType;
    /** 卡片JSON，含预览、待确认清单或执行结果；允许为空，表示尚无该项数据。*/
    private String payload;
    /** 工具调用名称；普通文本消息为空。 */
    private String toolName;
    /** 关联 dispatch_preview.id 的预览快照标识；允许为空，表示尚无该项数据。*/
    private String previewId;
    /** 关联 dispatch_plan.id 的派单清单标识；允许为空，表示尚无该项数据。 */
    private String planId;
    /** 实际使用的模型名称；允许为空，表示尚无该项数据。*/
    private String model;
    /** 模型输入token数；接口未返回用量时为空。 */
    private Integer promptTokens;
    /** 模型输出token数；接口未返回用量时为空。*/
    private Integer completionTokens;
    /** 本次处理耗时，单位毫秒；允许为空，表示尚无该项数据。 */
    private Integer latencyMs;
    /** 记录创建时间。*/
    private LocalDateTime createdAt;
}
