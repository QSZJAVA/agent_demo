package com.example.report.catalog;

/**
 * 报表名称解析的匹配结论；歧义必须交由用户澄清，不能任意选取首个候选。
 */
public enum MatchType {
    /** 正式名称、编码或 report_id 命中 */
    EXACT,
    /** 别名命中*/
    ALIAS,
    /** 模糊匹配唯一命中（错别字、近似说法） */
    FUZZY,
    /** 没有指定报表（或明确说全部报表）：当前用户可见的全部可派单报表*/
    ALL,
    /** 命中多张报表且无法区分：必须让用户选择，不允许自动挑一个 */
    AMBIGUOUS,
    /** 没有匹配（含报表不存在、已停用、无权限，三者对用户表现一致，不泄露报表是否存在）*/
    NONE;

    public boolean resolved() {
        return this == EXACT || this == ALIAS || this == FUZZY || this == ALL;
    }
}
