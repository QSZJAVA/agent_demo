package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 派单规则（数据化存储，运行期可改）
 */
@Data
@TableName("dispatch_rule")
public class DispatchRule {

    private String tenantId;

    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_DISABLED = "disabled";
    public static final String STATUS_DRAFT = "draft";
    public static final String ANY_COMPANY = "*";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 报表目录中的稳定标识 */
    private String reportId;
    /** 迁移前的 reportType（sales / receivable / expense），仅用于追溯；新规则为空 */
    private String legacyReportType;
    /** * 通配；具体公司的规则优先于通配 */
    private String companyCode;
    private String name;
    /** 给人和模型看的规则说明 */
    private String description;
    /** Aviator 表达式，在事实模型上求值 */
    private String expression;
    private Integer version;
    /** draft / published / disabled */
    private String status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
