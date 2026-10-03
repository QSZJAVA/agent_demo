package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 会话（用户可见的历史记录入口）
 */
@Data
@TableName("agent_conversation")
public class AgentConversation {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DELETED = "deleted";

    /** 服务端生成的会话主键。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 数据所属租户标识；查询和写入必须限定租户。*/
    private String tenantId;
    /** 记录所属用户标识，结合租户确定数据归属。 */
    private String userId;
    /** 会话标题，默认使用首条用户消息摘要；允许为空，表示尚无该项数据。*/
    private String title;
    /** 实际使用的模型名称；允许为空，表示尚无该项数据。 */
    private String model;
    /** 会话消息计数。*/
    private Integer messageCount;
    /** 最近一条消息的时间；无消息时为空。 */
    private LocalDateTime lastMessageAt;
    /** 会话状态：active可用，deleted已删除。*/
    private String status;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 记录最后更新时间。*/
    private LocalDateTime updatedAt;
}
