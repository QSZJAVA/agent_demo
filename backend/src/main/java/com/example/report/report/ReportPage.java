package com.example.report.report;

import java.util.List;

/**
 * 业务报表分页响应；统计与记录查询必须使用相同租户及公司权限范围。
 * @param records 当前页记录，空页返回空集合
 * @param total 授权范围内记录总数
 * @param page 页码，从1开始
 * @param size 每页条数
 * @param <T> 报表记录类型
 */
public record ReportPage<T>(List<T> records, long total, int page, int size) { }
