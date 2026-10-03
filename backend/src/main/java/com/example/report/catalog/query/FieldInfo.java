package com.example.report.catalog.query;

/**
 * 报表事实字段契约，供目录展示和规则表达式校验使用。
 * @param name 规则事实变量名，须与适配器返回的 facts 一致
 * @param type 字段数据类型标识
 * @param description 字段的业务含义与使用说明
 */
public record FieldInfo(String name, String type, String description) {
}
