package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表别名：简称、口语、英文名、历史名称、部门俗称、常见错别字。同一个别名可以挂在多张报表上，解析时视为歧义。
 */
@Data
@TableName("report_alias")
public class ReportAlias {

    private String tenantId;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reportId;
    private String alias;
    /** SHORT / COLLOQUIAL / ENGLISH / HISTORICAL / DEPARTMENT / TYPO */
    private String aliasType;
    private Integer priority;
    /** ACTIVE / DISABLED */
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
}
