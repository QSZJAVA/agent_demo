package com.example.report.dispatch;

/** 预览内的稳定记录标识，不能用展示单据号代替。 */
public record RecordKey(String reportId, String recordId) { }
