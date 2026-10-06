package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 预览快照中的一条记录：执行派单只认快照里的记录
 */
@Data
@TableName("dispatch_preview_item")
public class DispatchPreviewItem {

    /** 本表记录主键；数据库自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 dispatch_preview.id 的预览快照标识。*/
    private String previewId;
    /** 所属快照或清单内的展示顺序。 */
    private Integer seq;
    /** 关联 report_definition.report_id 的稳定报表标识。*/
    private String reportId;
    /** 记录生成时的报表名称快照。 */
    private String reportName;
    /** 该报表目录版本号；定义变化后旧快照需重新验证。*/
    private Long catalogVersion;
    /** 来源业务表记录标识，以字符串保留原主键。 */
    private String recordId;
    /** 来源业务单据号，用于展示和人工核对；允许为空，表示尚无该项数据。*/
    private String docNo;
    /** 业务记录所属公司代码；用于公司权限隔离；允许为空，表示尚无该项数据。 */
    private String companyCode;
    /** 业务记录展示摘要快照；允许为空，表示尚无该项数据。*/
    private String label;
    /** 交易对方实体快照JSON，结构为id、name、aliases；标识在所属租户和公司内稳定；空表示来源未提供客户实体。 */
    private String counterpartyJson;
    /** 已配置标量字段快照JSON数组，元素为name、type、value；value为空表示来源空值；随预览或清单保留，不重新读取来源。 */
    private String fieldsJson = "[]";
    /** 业务金额；小数精度2位，币种沿用来源业务账本；允许为空，表示尚无该项数据。 */
    private BigDecimal amount;
    /** 业务发生日期；按来源报表日期字段取值；允许为空，表示尚无该项数据。*/
    private LocalDate bizDate;
    /** 关联 dispatch_rule.id 的命中规则标识；允许为空，表示尚无该项数据。 */
    private Long ruleId;
    /** 命中规则名称快照；允许为空，表示尚无该项数据。*/
    private String ruleName;
    /** 命中规则的整数版本号；允许为空，表示尚无该项数据。 */
    private Integer ruleVersion;
    /** 命中规则说明快照；允许为空，表示尚无该项数据。*/
    private String ruleDescription;
}
