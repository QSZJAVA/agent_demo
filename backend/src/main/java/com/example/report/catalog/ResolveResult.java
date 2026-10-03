package com.example.report.catalog;

import java.util.List;

/**
 * 报表解析结果：把用户对报表的说法变成结构化范围。只包含当前用户可见报表的信息。
 *
 * @param reports             已确定的报表（EXACT / ALIAS / FUZZY / ALL）
 * @param candidates          需要用户选择的候选（AMBIGUOUS）
 * @param preselected         候选中已经唯一确定、选择卡片上默认勾选的报表
 * @param matchedTerms        命中的说法原文
 * @param unrecognized        用户说了、但在可见目录里没有对应报表的片段（提示用，不影响已识别部分）
 * @param noAccessibleReports 当前账号没有任何可访问的可派单报表
 * @param matchType 名称匹配类型；歧义或未识别不能自动执行
 * @param query 用户对报表的说法或本次解析输入
 */
public record ResolveResult(
        MatchType matchType,
        String query,
        List<ReportRef> reports,
        List<ReportRef> candidates,
        List<String> preselected,
        List<String> matchedTerms,
        List<String> unrecognized,
        boolean noAccessibleReports
) {

    public ResolveResult {
        reports = reports == null ? List.of() : List.copyOf(reports);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        preselected = preselected == null ? List.of() : List.copyOf(preselected);
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        unrecognized = unrecognized == null ? List.of() : List.copyOf(unrecognized);
    }

    public boolean resolved() {
        return matchType.resolved();
    }

    public List<String> reportIds() {
        return reports.stream().map(ReportRef::reportId).toList();
    }

    static ResolveResult none(String query, boolean noAccessibleReports) {
        return new ResolveResult(MatchType.NONE, query, null, null, null, null, null, noAccessibleReports);
    }
}
