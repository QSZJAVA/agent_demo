package com.example.report.dispatch;

/**
 * 跨报表记录的完整标识；排除操作同时绑定报表与记录，避免不同报表相同主键互相影响。
 * @param reportId 稳定报表标识
 * @param recordId 该报表内的来源记录主键字符串
 */
public record RecordKey(String reportId, String recordId) { }
