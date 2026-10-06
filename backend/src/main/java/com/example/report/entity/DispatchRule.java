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

    /** 数据所属租户标识；查询和写入必须限定租户。 */
    private String tenantId;

    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_DISABLED = "disabled";
    public static final String STATUS_DRAFT = "draft";
    public static final String ANY_COMPANY = "*";

    /** 本表记录主键；数据库自增。*/
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 report_definition.report_id 的稳定报表标识。 */
    private String reportId;
    /** 规则公司范围；*表示通配，具体公司规则优先。 */
    private String companyCode;
    /** 规则展示名称。*/
    private String name;
    /** 供用户理解的规则说明；允许为空，表示尚无该项数据。 */
    private String description;
    /** Aviator规则表达式，只在已验证的业务事实字段上求值。*/
    private String expression;
    /** 规则整数版本；同租户、报表、公司范围内唯一。 */
    private Integer version;
    /** 规则状态：draft草稿、published已发布、disabled停用。*/
    private String status;
    /** 生效开始时间，含此时刻；空表示不限制开始时间。 */
    private LocalDateTime effectiveFrom;
    /** 生效结束时间，不含此时刻；空表示不限制结束时间。*/
    private LocalDateTime effectiveTo;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 最后修改操作的用户标识；允许为空，表示尚无该项数据。*/
    private String updatedBy;
    /** 记录最后更新时间；允许为空，表示尚无该项数据。 */
    private LocalDateTime updatedAt;
}
