package com.example.report.catalog;

import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用报表解析（P0-03）：精确、别名、模糊、歧义、无匹配五类结果；只在可见目录内匹配（P0-04）
 */
class ReportResolverTest {

    private final ReportResolver resolver = new ReportResolver(0.6, 0.15);
    private final List<CatalogEntry> demo = TestCatalog.demo();
    private final TermIndex terms = TestCatalog.terms(demo);
    private final List<ReportRef> all = demo.stream().map(CatalogEntry::ref).toList();

    private ResolveResult resolve(String query) {
        return resolver.resolve(query, terms, all);
    }

    private static List<String> names(List<ReportRef> refs) {
        return refs.stream().map(ReportRef::reportName).toList();
    }

    @ParameterizedTest
    @CsvSource({"销售报表,销售报表", "应收报表,应收报表", "sales,销售报表", "RECEIVABLE,应收报表",
            "查一下费用报表有哪些可以派单,费用报表", "rpt-expense-claim,费用报表"})
    void exactNameCodeOrIdMatchesUniquely(String query, String expected) {
        ResolveResult r = resolve(query);
        assertEquals(MatchType.EXACT, r.matchType(), query);
        assertEquals(List.of(expected), names(r.reports()));
    }

    @ParameterizedTest
    @CsvSource({"销售台账,销售报表", "订单销售表,销售报表", "sales report,销售报表", "应收发票台账,应收报表",
            "AR,应收报表", "报销单,费用报表", "应收保表,应收报表", "查一下报销的有哪些,费用报表"})
    void aliasesShortNamesHistoricalNamesAndTyposResolveToTheSameReport(String query, String expected) {
        ResolveResult r = resolve(query);
        assertEquals(MatchType.ALIAS, r.matchType(), query);
        assertEquals(List.of(expected), names(r.reports()));
    }

    @ParameterizedTest
    @CsvSource({"销兽报表,销售报表", "应手报表,应收报表", "费甬报表,费用报表"})
    void typosFallBackToFuzzyMatching(String query, String expected) {
        ResolveResult r = resolve(query);
        assertEquals(MatchType.FUZZY, r.matchType(), query);
        assertEquals(List.of(expected), names(r.reports()));
    }

    @Test
    void sharedAliasIsAmbiguousAndNeverAutoSelected() {
        // “客户对账”同时是销售报表、应收报表的别名：必须让用户选择（T-RESOLVE-03）
        ResolveResult r = resolve("查客户对账");
        assertEquals(MatchType.AMBIGUOUS, r.matchType());
        assertTrue(r.reports().isEmpty());
        assertEquals(List.of("销售报表", "应收报表"), names(r.candidates()));
    }

    @Test
    void currentNameWinsOverAnotherReportsAlias() {
        TermIndex index = TermIndex.build(List.of(
                new TermIndex.ReportTerms("sales", "销售台账", "SALES", List.of()),
                new TermIndex.ReportTerms("receivable", "应收报表", "AR",
                        List.of(new TermIndex.AliasTerm("销售台账", 0)))));
        List<ReportRef> visible = List.of(
                new ReportRef("sales", "销售台账", "SALES", null),
                new ReportRef("receivable", "应收报表", "AR", null));
        ResolveResult result = resolver.resolve("查销售台账", index, visible);
        assertEquals(MatchType.EXACT, result.matchType());
        assertEquals(List.of("销售台账"), names(result.reports()));
    }

    @Test
    void closeFuzzyScoresAreAmbiguousToo() {
        ResolveResult r = resolve("对账");
        assertEquals(MatchType.AMBIGUOUS, r.matchType());
        assertEquals(List.of("销售报表", "应收报表"), names(r.candidates()));
    }

    @Test
    void ambiguityWithAnUnambiguousPartPreselectsIt() {
        ResolveResult r = resolve("客户对账和费用");
        assertEquals(MatchType.AMBIGUOUS, r.matchType());
        assertEquals(List.of("销售报表", "应收报表", "费用报表"), names(r.candidates()));
        assertEquals(List.of(TestCatalog.EXPENSE), r.preselected());
    }

    @Test
    void compoundRequestReturnsSeveralExplicitReports() {
        // “查销售和应收”返回两张明确的报表（T-RESOLVE-07）
        ResolveResult r = resolve("查销售和应收");
        assertEquals(MatchType.ALIAS, r.matchType());
        assertEquals(List.of("销售报表", "应收报表"), names(r.reports()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"查XX", "采购", "你好", "查一下库存报表"})
    void unknownReportIsNoMatch(String query) {
        ResolveResult r = resolve(query);
        assertEquals(MatchType.NONE, r.matchType(), query);
        assertTrue(r.reports().isEmpty() && r.candidates().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "全部报表", "所有报表", "全部", "all", "查一下我有哪些可以派单", "查我能派单的报表"})
    void noSpecificReportMeansAllVisibleReports(String query) {
        ResolveResult r = resolve(query);
        assertEquals(MatchType.ALL, r.matchType(), query);
        assertEquals(List.of("销售报表", "应收报表", "费用报表"), names(r.reports()));
    }

    @Test
    void partlyUnrecognizedRequestReportsTheUnknownPart() {
        ResolveResult r = resolve("销售报表和采购报表");
        assertEquals(List.of("销售报表"), names(r.reports()));
        assertEquals(List.of("采购"), r.unrecognized());
        // 单据号不是报表说法，不提示“未识别”
        assertTrue(resolve("销售报表 SO2026002").unrecognized().isEmpty());
    }

    @Test
    void reportsOutsideTheVisibleCatalogAreNeverMatched() {
        // 没有应收报表权限：应收的名称、别名、编码都匹配不到，结果与“不存在”完全一样（T-RESOLVE-04）
        List<ReportRef> noReceivable = all.stream().filter(r -> !r.reportId().equals(TestCatalog.RECEIVABLE)).toList();
        for (String query : List.of("应收报表", "应收", "AR", "receivable", "rpt-ar-invoice", "应收保表")) {
            ResolveResult r = resolver.resolve(query, terms, noReceivable);
            assertEquals(MatchType.NONE, r.matchType(), query);
            assertTrue(r.candidates().isEmpty(), "不能以候选的形式泄露无权限报表");
        }
        // 共享别名只剩可见的那一张：不再有歧义
        ResolveResult shared = resolver.resolve("客户对账", terms, noReceivable);
        assertEquals(MatchType.ALIAS, shared.matchType());
        assertEquals(List.of("销售报表"), names(shared.reports()));
    }

    @Test
    void userWithoutAnyReportGetsNoAccess() {
        ResolveResult r = resolver.resolve("销售报表", terms, List.of());
        assertEquals(MatchType.NONE, r.matchType());
        assertTrue(r.noAccessibleReports());
    }

    @Test
    void sqlOrTableNamesInTheQueryNeverBecomeAnythingButCatalogReports() {
        // 模型或用户写进 reportQuery 的表名、SQL 只是一段文本：结果只可能是不匹配或可见目录里的报表，
        // 解析结果里只有 report_id，后续查询用的是目录配置，文本本身永远不会进入 SQL（P0-05）
        for (String query : List.of("report_sales; DROP TABLE report_sales", "select * from report_expense",
                "report_receivable", "1=1 OR TRUE")) {
            ResolveResult r = resolve(query);
            assertTrue(all.containsAll(r.reports()) && all.containsAll(r.candidates()), query);
        }
        // 表名里的 sales 不是一个独立的词，不会按报表编码精确命中
        assertTrue(resolve("report_sales").matchType() != MatchType.EXACT);
    }

    @Test
    void englishAliasNeedsWordBoundaries() {
        // “ar” 不能命中 clear / report_ar 里的片段
        assertEquals(MatchType.NONE, resolve("clear").matchType());
        assertEquals(MatchType.ALIAS, resolve("查 AR 报表").matchType());
    }
}
