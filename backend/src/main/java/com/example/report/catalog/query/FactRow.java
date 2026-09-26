package com.example.report.catalog.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 一条待判定的记录：展示字段 + 事实模型（facts 的 key 就是规则表达式里可用的变量名）
 *
 * @param recordId 报表内的记录主键，统一按字符串处理（不同来源的主键类型不同）
 */
public record FactRow(
        String recordId,
        String docNo,
        String companyCode,
        String label,
        BigDecimal amount,
        LocalDate date,
        Map<String, Object> facts
) {
}
