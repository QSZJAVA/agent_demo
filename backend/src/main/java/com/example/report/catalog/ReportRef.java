package com.example.report.catalog;

/**
 * 报表展示与语义解析使用的轻量引用；不向模型暴露来源表名或查询配置。
 * @param reportId 稳定的目录标识
 * @param reportName 当前展示名称
 * @param domainCode 业务域编码
 * @param description 报表业务说明，未配置时可为空
 */
public record ReportRef(String reportId, String reportName, String domainCode, String description) {
}
