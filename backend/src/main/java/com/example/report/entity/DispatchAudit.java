package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 派单审计：每条记录一行，通过 conversationId / previewId / planId 关联到触发它的对话
 */
@Data
@TableName("dispatch_audit")
public class DispatchAudit {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String conversationId;
    private String previewId;
    private String planId;
    /** agent / manual */
    private String source;
    private String reportType;
    private Long recordId;
    private String docNo;
    private String companyCode;
    private BigDecimal amount;
    private String ruleName;
    private Integer ruleVersion;
    private Boolean success;
    private String message;
    private LocalDateTime createdAt;
}
