package com.example.report.rule.fact;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 一条待判定的记录：展示字段 + 事实模型（facts 的 key 就是规则表达式里可用的变量名）
 */
public record FactRow(
        Long recordId,
        String docNo,
        String companyCode,
        String label,
        BigDecimal amount,
        LocalDate date,
        Map<String, Object> facts
) {
}
