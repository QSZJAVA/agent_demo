package com.example.report.rule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 命中派单规则的候选记录（写入预览快照、待确认清单与审计）
 *
 * @param reportId       报表目录中的稳定标识
 * @param recordId       报表内的记录主键
 * @param counterparty 冻结的交易对方实体；null表示当前来源记录没有提供客户实体
 * @param fields 已配置标量字段的完整快照；空集合表示该候选未声明字段，不从展示列猜测补齐
 * @param catalogVersion 生成候选时该报表的目录版本
 * @param reportName 报表展示名称
 * @param docNo 来源业务单据号，可空时表示来源未提供
 * @param companyCode 公司代码；查询范围为空时表示当前用户全部可见公司
 * @param label 业务记录展示摘要，来源未配置时可为空
 * @param amount 业务金额，保留精确十进制；币种沿用来源账本
 * @param date 来源业务日期，未配置时可为空
 * @param ruleId 命中的规则标识，人工派单不套规则时可为空
 * @param ruleName 命中的规则名称快照
 * @param ruleVersion 命中的规则版本号；未匹配规则时可为空
 * @param ruleDescription 命中规则的业务说明
 */
public record Candidate(
        String reportId,
        String reportName,
        String recordId,
        String docNo,
        String companyCode,
        String label,
        BigDecimal amount,
        LocalDate date,
        Long ruleId,
        String ruleName,
        Integer ruleVersion,
        String ruleDescription,
        Long catalogVersion,
        CounterpartyRef counterparty,
        java.util.List<FieldFact> fields
) {
    public Candidate { fields=java.util.List.copyOf(fields); }
    /** 仅供不含字段筛选的确定性调用构造候选；生产来源统一通过目录捕获字段快照。 */
    public Candidate(String reportId,String reportName,String recordId,String docNo,String companyCode,String label,
                     BigDecimal amount,LocalDate date,Long ruleId,String ruleName,Integer ruleVersion,String ruleDescription,Long catalogVersion,CounterpartyRef counterparty) {
        this(reportId,reportName,recordId,docNo,companyCode,label,amount,date,ruleId,ruleName,ruleVersion,ruleDescription,catalogVersion,counterparty,java.util.List.of());
    }

    /** 不含客户实体的当前报表候选构造器；不能用于把客户名称伪装成稳定标识。 */
    public Candidate(String reportId,String reportName,String recordId,String docNo,String companyCode,String label,
                     BigDecimal amount,LocalDate date,Long ruleId,String ruleName,Integer ruleVersion,String ruleDescription,Long catalogVersion) {
        this(reportId,reportName,recordId,docNo,companyCode,label,amount,date,ruleId,ruleName,ruleVersion,ruleDescription,catalogVersion,null);
    }
    /** 跨报表唯一的记录键 */
    public String key() {
        return reportId + ":" + recordId;
    }
}
