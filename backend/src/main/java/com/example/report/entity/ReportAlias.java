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

    /** 数据所属租户标识；查询和写入必须限定租户。 */
    private String tenantId;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    /** 本表记录主键；数据库自增。*/
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 report_definition.report_id 的稳定报表标识。 */
    private String reportId;
    /** 报表简称、口语名、英文名或历史名称。*/
    private String alias;
    /** 别名类型：SHORT、COLLOQUIAL、ENGLISH、HISTORICAL、DEPARTMENT、TYPO。 */
    private String aliasType;
    /** 候选排序优先级；数值越大越靠前。*/
    private Integer priority;
    /** 别名状态：ACTIVE启用、DISABLED停用。 */
    private String status;
    /** 创建操作的用户标识；允许为空，表示尚无该项数据。*/
    private String createdBy;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
}
