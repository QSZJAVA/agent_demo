package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 派单审计：每条记录每次派单一行。单独一行就能回答：谁（租户 / 用户）、在哪个会话、对哪张报表、
 * 依据哪个预览 / 清单 / 清单条目、命中哪条规则、预览时的目录 / 规则 / 权限版本、结果与原因、对应哪次请求。
 */
@Data
@TableName("dispatch_audit")
public class DispatchAudit {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILED = "FAILED";
    /** 执行前复核不通过，没有调用派单接口 */
    public static final String OUTCOME_SKIPPED = "SKIPPED";

    /** 本表记录主键；数据库自增。*/
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联trace_event.id的不可变证据标识；历史记录可为空。 */
    private Long evidenceId;
    /** 清单执行轮次；每次认领执行或重试递增，旧轮次不得回写；允许为空，表示尚无该项数据。*/
    private Long executionVersion;
    /** 条目派单尝试次数；每次准备发送时递增；允许为空，表示尚无该项数据。 */
    private Integer attemptCount;
    /** 产生证据时的业务阶段，例如发送或结果核对；允许为空，表示尚无该项数据。*/
    private String phase;
    /** 规则快照JSON，含标识、版本、表达式及来源；空表示历史证据未核实。 */
    private String ruleSnapshot;
    /** 数据所属租户标识；查询和写入必须限定租户。*/
    private String tenantId;
    /** 记录所属用户标识，结合租户确定数据归属。 */
    private String userId;
    /** 关联 agent_conversation.id 的会话标识；允许为空，表示尚无该项数据。*/
    private String conversationId;
    /** 关联 dispatch_preview.id 的预览快照标识；允许为空，表示尚无该项数据。 */
    private String previewId;
    /** 关联 dispatch_plan.id 的派单清单标识；允许为空，表示尚无该项数据。*/
    private String planId;
    /** 关联 dispatch_plan_item.id 的清单条目标识；允许为空，表示尚无该项数据。 */
    private Long planItemId;
    /** 派单入口来源：agent自动清单、manual人工选择。*/
    private String source;
    /** 关联 report_definition.report_id 的稳定报表标识。 */
    private String reportId;
    /** 记录生成时的报表名称快照；允许为空，表示尚无该项数据。*/
    private String reportName;
    /** 历史 reportType 编码；新记录为空，仅用于存量追溯。 */
    private String legacyReportType;
    /** 来源业务表记录标识，以字符串保留原主键；允许为空，表示尚无该项数据。*/
    private String recordId;
    /** 来源业务单据号，用于展示和人工核对；允许为空，表示尚无该项数据。 */
    private String docNo;
    /** 业务记录所属公司代码；用于公司权限隔离；允许为空，表示尚无该项数据。*/
    private String companyCode;
    /** 业务金额；小数精度2位，币种沿用来源业务账本；允许为空，表示尚无该项数据。 */
    private BigDecimal amount;
    /** 关联 dispatch_rule.id 的命中规则标识；允许为空，表示尚无该项数据。*/
    private Long ruleId;
    /** 命中规则名称快照；允许为空，表示尚无该项数据。 */
    private String ruleName;
    /** 命中规则的整数版本号；允许为空，表示尚无该项数据。*/
    private Integer ruleVersion;
    /** 该报表目录版本号；定义变化后旧快照需重新验证；允许为空，表示尚无该项数据。 */
    private Long catalogVersion;
    /** 预览范围内生效规则版本的聚合指纹；允许为空，表示尚无该项数据。*/
    private String ruleFingerprint;
    /** 用户公司与报表权限的版本指纹，用于识别授权变化；允许为空，表示尚无该项数据。 */
    private String permissionVersion;
    /** 是否成功：1成功，0失败或跳过；详细结果见outcome。*/
    private Boolean success;
    /** 结果：SUCCESS成功、FAILED失败、SKIPPED复核未通过、UNKNOWN结果待核对。 */
    private String outcome;
    /** 失败原因业务编码；允许为空，表示尚无该项数据。*/
    private String errorCode;
    /** 发往业务服务的稳定幂等请求号；核对和重试沿用此号；允许为空，表示尚无该项数据。 */
    private String externalRequestId;
    /** 操作结果或失败原因摘要；允许为空，表示尚无该项数据。*/
    private String message;
    /** 请求链路标识，关联应用日志；允许为空，表示尚无该项数据。 */
    private String traceId;
    /** 记录创建时间。*/
    private LocalDateTime createdAt;
}
