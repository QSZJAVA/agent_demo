package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则变更历史：每次发布 / 停用 / 回滚落一条
 */
@Data
@TableName("dispatch_rule_history")
public class DispatchRuleHistory {

    /** 数据所属租户标识；查询和写入必须限定租户。 */
    private String tenantId;

    /** 本表记录主键；数据库自增。*/
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 dispatch_rule.id 的命中规则标识。 */
    private Long ruleId;
    /** 关联 report_definition.report_id 的稳定报表标识。*/
    private String reportId;
    /** 历史 reportType 编码；新记录为空，仅用于存量追溯。 */
    private String legacyReportType;
    /** 该历史规则的公司范围；*表示通配。*/
    private String companyCode;
    /** 操作涉及的规则整数版本。 */
    private Integer version;
    /** 操作时的规则名称快照；允许为空，表示尚无该项数据。*/
    private String name;
    /** 操作时的Aviator规则表达式快照。 */
    private String expression;
    /** 操作时的规则说明快照；允许为空，表示尚无该项数据。*/
    private String description;
    /** 规则变更类型：publish发布、disable停用、rollback回滚。 */
    private String action;
    /** 执行规则变更的用户标识；允许为空，表示尚无该项数据。*/
    private String operatedBy;
    /** 规则变更操作时间；允许为空，表示尚无该项数据。 */
    private LocalDateTime operatedAt;
}
