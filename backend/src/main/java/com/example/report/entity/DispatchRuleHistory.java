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

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private String reportType;
    private String companyCode;
    private Integer version;
    private String name;
    private String expression;
    private String description;
    /** publish / disable / rollback */
    private String action;
    private String operatedBy;
    private LocalDateTime operatedAt;
}
