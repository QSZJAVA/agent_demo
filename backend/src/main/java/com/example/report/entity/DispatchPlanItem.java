package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 待确认清单条目与逐条执行结果：发送前保存 UNKNOWN，再更新为 SUCCESS / FAILED。
 * 复核不通过则 SKIPPED；UNKNOWN 表示结果不明，必须先核对，不能直接重发。
 */
@Data
@TableName("dispatch_plan_item")
public class DispatchPlanItem {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";
    /** 网关超时或连接中断后无法判断外部系统是否已受理，禁止自动重试。 */
    public static final String UNKNOWN = "UNKNOWN";

    /** 本表记录主键；数据库自增。*/
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 规则快照JSON，含标识、版本、表达式及来源；无规则的手工派单为空；命中规则时为空表示当前证据缺失。 */
    private String ruleSnapshot;
    /** 关联 dispatch_plan.id 的派单清单标识。*/
    private String planId;
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
    /** 条目状态：PENDING待执行、SUCCESS成功、FAILED失败、SKIPPED未发送、UNKNOWN发送后结果不明。*/
    private String status;
    /** 条目派单尝试次数；每次准备发送时递增。 */
    private Integer attemptCount = 0;
    /** 发往业务服务的稳定幂等请求号；核对和重试沿用此号；允许为空，表示尚无该项数据。*/
    private String externalRequestId;
    /** 失败原因业务编码；允许为空，表示尚无该项数据。 */
    private String errorCode;
    /** 失败详情摘要；供人工核对，不包含凭据；允许为空，表示尚无该项数据。*/
    private String errorMessage;
    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;
}
