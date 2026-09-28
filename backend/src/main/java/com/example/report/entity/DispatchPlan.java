package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待确认派单清单：PENDING → EXECUTING → EXECUTED；PENDING → CANCELLED / EXPIRED
 */
@Data
@TableName("dispatch_plan")
public class DispatchPlan {

    public static final String PENDING = "PENDING";
    public static final String EXECUTING = "EXECUTING";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String EXECUTED = "EXECUTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    @TableId(type = IdType.INPUT)
    private String id;
    /** 0 为升级前历史清单，1 表示状态与追溯事件原子持久化。 */
    private Integer evidenceVersion = 0;
    private String previewId;
    private String tenantId;
    private String userId;
    private String conversationId;
    private String status;
    private String statusReason;
    /** 每次认领执行或重试递增，防止旧核对请求跨执行轮次写入。 */
    private Long executionVersion = 0L;
    /** 排除的单据号 JSON 数组 */
    private String excludeJson;
    private Integer itemCount;
    private Integer successCount;
    private Integer failedCount;
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private String confirmedBy;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
