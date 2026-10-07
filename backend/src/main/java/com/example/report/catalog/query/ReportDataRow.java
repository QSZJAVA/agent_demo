package com.example.report.catalog.query;

/**
 * 通用报表查询的一条来源事实，包含已派单和未派单记录，不等同于派单资格。
 * @param fact 来源记录及目录字段，不包含未授权列
 * @param status 当前来源派单状态：未派单、已派单或来源提供的其他状态
 */
public record ReportDataRow(FactRow fact,String status) { }
