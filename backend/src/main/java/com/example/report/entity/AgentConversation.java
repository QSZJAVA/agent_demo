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

    /** 服务端生成的 32 位 ID */
    @TableId(type = IdType.INPUT)
    private String id;
    private String tenantId;
    private String userId;
    private String title;
    private String model;
    private Integer messageCount;
    private LocalDateTime lastMessageAt;
    /** active / deleted */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
