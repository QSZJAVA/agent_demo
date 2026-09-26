package com.example.report.rule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 命中派单规则的候选记录（写入预览快照、待确认清单与审计）
 *
 * @param reportId       报表目录中的稳定标识
 * @param recordId       报表内的记录主键
 * @param catalogVersion 生成候选时该报表的目录版本
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
        Long catalogVersion
) {

    /** 跨报表唯一的记录键 */
    public String key() {
        return reportId + ":" + recordId;
    }
}
