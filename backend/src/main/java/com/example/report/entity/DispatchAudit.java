package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 派单审计：每条记录每次派单一行。单独一行就能回答：谁（租户 / 用户）、在哪个会话、对哪张报表、
 * 依据哪个预览 / 清单 / 清单条目、命中哪条规则、预览时的目录 / 规则 / 权限版本、结果与原因、对应哪次请求。
 */
@Data
@TableName("dispatch_audit")
public class DispatchAudit {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILED = "FAILED";
    /** 执行前复核不通过，没有调用派单接口 */
    public static final String OUTCOME_SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String userId;
    private String conversationId;
    private String previewId;
    private String planId;
    private Long planItemId;
    /** agent / manual */
    private String source;
    private String reportId;
    private String reportName;
    /** 迁移前的 reportType，仅存量记录有值 */
    private String legacyReportType;
    private String recordId;
    private String docNo;
    private String companyCode;
    private BigDecimal amount;
    private Long ruleId;
    private String ruleName;
    private Integer ruleVersion;
    private Long catalogVersion;
    private String ruleFingerprint;
    private String permissionVersion;
    private Boolean success;
    /** SUCCESS / FAILED / SKIPPED */
    private String outcome;
    private String errorCode;
    private String externalRequestId;
    private String message;
    private String traceId;
    private LocalDateTime createdAt;
}
