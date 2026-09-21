package com.example.report.rule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 命中派单规则的候选记录（写入预览快照、待确认清单与审计）
 */
public record Candidate(
        String reportType,
        String reportName,
        Long recordId,
        String docNo,
        String companyCode,
        String label,
        BigDecimal amount,
        LocalDate date,
        String ruleName,
        Integer ruleVersion,
        String ruleDescription
) {
}
