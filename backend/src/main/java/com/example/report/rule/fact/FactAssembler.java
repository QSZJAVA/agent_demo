package com.example.report.rule.fact;

import com.example.report.report.ReportType;

import java.util.List;
import java.util.Set;

/**
 * 事实模型装配器：由开发维护。负责 SQL 粗筛（公司范围、未派单）并把行装配成事实模型。
 * 规则表达式只在 facts 上求值，需要新字段时才改这里。
 */
public interface FactAssembler {

    ReportType type();

    /** 规则里可用的字段 */
    List<FieldInfo> fields();

    /** 粗筛：只取指定公司、未派单的记录 */
    List<FactRow> rows(Set<String> companies);
}
