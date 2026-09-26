package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表目录：每张报表一行。report_id 是稳定标识，名称、编码、别名都是可维护数据。
 */
@Data
@TableName("report_definition")
public class ReportDefinition {

    private String tenantId;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";
    /** 标准报表：query_config 描述表与字段映射，不写代码 */
    public static final String MODE_STANDARD = "STANDARD";
    /** 复杂报表：query_config 指定专用适配器 */
    public static final String MODE_ADAPTER = "ADAPTER";

    /** 稳定业务标识，创建后不可修改 */
    @TableId(type = IdType.INPUT)
    private String reportId;
    /** 对外 / 接口编码 */
    private String reportCode;
    private String reportName;
    private String domainCode;
    private String description;
    /** STANDARD / ADAPTER */
    private String queryMode;
    /** 查询配置 JSON */
    private String queryConfig;
    /** 是否允许通过 Agent 发起派单 */
    private Boolean dispatchEnabled;
    /** DRAFT / PUBLISHED / DISABLED */
    private String status;
    private Integer schemaVersion;
    /** 定义每变更一次加 1 */
    private Long catalogVersion;
    /** 访问该报表需要的权限码 */
    private String permissionCode;
    private Integer sortOrder;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String ownerUserId;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
