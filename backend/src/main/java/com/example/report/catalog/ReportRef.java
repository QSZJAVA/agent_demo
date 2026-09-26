package com.example.report.catalog;

/**
 * 可以展示给用户和模型的报表摘要。只会为当前用户可见的报表生成。
 */
public record ReportRef(String reportId, String reportName, String domainCode, String description) {
}
