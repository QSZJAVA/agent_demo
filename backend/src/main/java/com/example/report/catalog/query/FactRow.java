package com.example.report.catalog.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 一条待判定的记录：展示字段 + 事实模型（facts 的 key 就是规则表达式里可用的变量名）
 *
 * @param recordId 报表内的记录主键，统一按字符串处理（不同来源的主键类型不同）
 * @param docNo 来源业务单据号，可空时表示来源未提供
 * @param companyCode 公司代码；查询范围为空时表示当前用户全部可见公司
 * @param label 业务记录展示摘要，来源未配置时可为空
 * @param amount 业务金额，保留精确十进制；币种沿用来源账本
 * @param date 来源业务日期，未配置时可为空
 * @param facts 规则可用的业务事实映射，字段必须符合报表事实契约
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
