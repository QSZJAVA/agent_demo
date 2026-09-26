package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 派单预览快照（服务端状态的唯一事实来源）：ACTIVE → SUPERSEDED / EXPIRED / CONSUMED
 */
@Data
@TableName("dispatch_preview")
public class DispatchPreview {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CONSUMED = "CONSUMED";

    @TableId(type = IdType.INPUT)
    private String id;
    private String tenantId;
    private String userId;
    private String conversationId;
    /** agent / fallback / selection / api */
    private String source;
    /** 预览范围 report_id 的 JSON 数组 */
    private String reportIds;
    /** 公司范围的 JSON 数组 */
    private String companyCodes;
    /** 统一预览请求 JSON */
    private String queryJson;
    private String catalogVersion;
    private String ruleVersion;
    private String permissionVersion;
    private String status;
    private String statusReason;
    private Integer totalCount;
    private BigDecimal totalAmount;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
