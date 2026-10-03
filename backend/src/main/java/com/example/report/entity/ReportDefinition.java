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

    /** 数据所属租户标识；查询和写入必须限定租户。 */
    private String tenantId;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";
    /** 标准报表：query_config 描述表与字段映射，不写代码*/
    public static final String MODE_STANDARD = "STANDARD";
    /** 复杂报表：query_config 指定专用适配器 */
    public static final String MODE_ADAPTER = "ADAPTER";

    /** 稳定报表主键；创建后不得因改名或改编码变化。*/
    @TableId(type = IdType.INPUT)
    private String reportId;
    /** 租户内唯一的对外接口编码。 */
    private String reportCode;
    /** 当前报表展示名称。*/
    private String reportName;
    /** 业务域编码，例如sales、receivable、expense、purchase。 */
    private String domainCode;
    /** 报表业务用途说明；允许为空，表示尚无该项数据。*/
    private String description;
    /** 查询模式：STANDARD字段映射、ADAPTER专用适配器。 */
    private String queryMode;
    /** 查询配置JSON；STANDARD含表及字段映射，ADAPTER含adapter编码。*/
    private String queryConfig;
    /** 是否允许派单：0否，1是；仍需发布状态与用户授权。 */
    private Boolean dispatchEnabled;
    /** 目录状态：DRAFT草稿、PUBLISHED已发布、DISABLED停用。*/
    private String status;
    /** 业务事实字段及结果结构版本号。 */
    private Integer schemaVersion;
    /** 该报表目录版本号；定义变化后旧快照需重新验证。*/
    private Long catalogVersion;
    /** 访问该报表需要的业务权限码。 */
    private String permissionCode;
    /** 报表展示及汇总排序值。*/
    private Integer sortOrder;
    /** 生效开始时间，含此时刻；空表示不限制开始时间。 */
    private LocalDateTime effectiveFrom;
    /** 生效结束时间，不含此时刻；空表示不限制结束时间。*/
    private LocalDateTime effectiveTo;
    /** 报表业务负责人用户标识；允许为空，表示尚无该项数据。 */
    private String ownerUserId;
    /** 创建操作的用户标识；允许为空，表示尚无该项数据。*/
    private String createdBy;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 最后修改操作的用户标识；允许为空，表示尚无该项数据。*/
    private String updatedBy;
    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;
}
