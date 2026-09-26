package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 待确认清单条目与逐条执行结果：PENDING → SUCCESS / FAILED / SKIPPED（执行前复核不通过，未调用派单接口）
 */
@Data
@TableName("dispatch_plan_item")
public class DispatchPlanItem {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String planId;
    private Integer seq;
    private String reportId;
    private String reportName;
    private Long catalogVersion;
    private String recordId;
    private String docNo;
    private String companyCode;
    private String label;
    private BigDecimal amount;
    private LocalDate bizDate;
    private Long ruleId;
    private String ruleName;
    private Integer ruleVersion;
    private String status;
    private Integer attemptCount;
    private String externalRequestId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime updatedAt;
}
