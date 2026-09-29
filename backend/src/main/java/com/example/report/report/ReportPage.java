package com.example.report.report;

import java.util.List;

public record ReportPage<T>(List<T> records, long total, int page, int size) { }
