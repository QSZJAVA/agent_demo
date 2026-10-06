package com.example.report.agent;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.TermIndex;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端兜底意图识别：报表说法全部来自目录（名称、编码、别名），句式与具体报表无关。
 * 用例与引入报表目录之前的正则版完全一致，证明替换没有丢失任何说法；另加新报表、权限相关用例。
 */
class PreviewIntentDetectorTest {

    private static final List<CatalogEntry> DEMO = TestCatalog.demo();
    private static final TermIndex TERMS = TestCatalog.terms(DEMO);
    private static final Set<String> ALL_VISIBLE = DEMO.stream().map(CatalogEntry::reportId).collect(Collectors.toSet());
    private static final Map<String, String> CODES = Map.of(
            "sales", TestCatalog.SALES, "receivable", TestCatalog.RECEIVABLE, "expense", TestCatalog.EXPENSE);

    private static Optional<PreviewIntentDetector.PreviewIntent> detect(String message) {
        return PreviewIntentDetector.detect(message, TERMS, ALL_VISIBLE);
    }

    /** 期望值写成 all 或 "sales|receivable" 形式（旧编码，便于与原用例对照），all 表示全部报表 */
    private static List<String> ids(String expected) {
        if ("all".equals(expected)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String code : expected.split("\\|")) {
            ids.add(CODES.get(code));
        }
        return ids;
    }

    @ParameterizedTest
    @CsvSource({
            "我说销售报表,sales", "我说的是销售报表,sales", "我指的是销售,sales",
            "只看应收报表,receivable", "改成费用报表,expense", "切换到全部报表,all",
            "销售报表有啥能派单的,sales", "销售报表有哪些可以派单的,sales",
            "销售报表有哪些可以派单的？,sales", "查一下费用报表有哪些可以派单,expense",
            "帮我查询应收报表的待派单记录,receivable", "销售报表,sales", "全部报表,all",
            "只看销售报表吧,sales", " SALES ,sales", "查一下我B公司有哪些能派单的,all",
            "销售报表和应收报表有哪些可以派单,sales|receivable",
            "查一下应收、费用报表有哪些可以派单,receivable|expense",
            "应收+费用,receivable|expense",
            "费用报表和销售报表,expense|sales"
    })
    void resolvesExplicitPreviewScope(String message, String expected) {
        assertEquals(ids(expected), detect(message).orElseThrow().reportIds());
    }

    @ParameterizedTest
    @CsvSource({
            "加上费用报表的,expense", "再加上应收报表,receivable", "还要看费用报表的,expense",
            "顺便查一下销售报表的,sales", "另外加上应收报表和费用报表,receivable|expense",
            "费用报表呢,expense", "那应收呢,receivable", "费用报表吗,expense", "销售呢,sales"
    })
    void resolvesScopeAppendRequests(String message, String expected) {
        PreviewIntentDetector.PreviewIntent intent = detect(message).orElseThrow();
        assertEquals(ids(expected), intent.reportIds());
        assertTrue(intent.append(), "追加范围必须标记为 append，服务端才会与上一轮范围合并");
    }

    @ParameterizedTest
    @CsvSource({
            "应收报表再查下,receivable", "应收报表再查一下,receivable",
            "应收报表重查一下,receivable", "销售报表再看看,sales",
            "费用报表刷新一下,expense", "应收报表查一下,receivable",
            "B公司应收报表再查下,receivable"
    })
    void resolvesFollowUpReQueryRequests(String message, String expected) {
        PreviewIntentDetector.PreviewIntent intent = detect(message).orElseThrow();
        assertEquals(ids(expected), intent.reportIds());
        assertFalse(intent.append(), "重新查这张报表是替换范围，不是追加");
        assertFalse(intent.remove());
    }

    @Test
    void marksOnlyAppendRequestsAsAppend() {
        assertFalse(detect("我说销售报表").orElseThrow().append());
        assertFalse(detect("查一下应收和费用报表有哪些能派单").orElseThrow().append());
        assertEquals(ids("receivable|expense"), detect("查一下应收和费用报表有哪些能派单").orElseThrow().reportIds());
        // 明确要求换一张报表时是替换，不是追加
        assertFalse(detect("只看费用报表呢").orElseThrow().append());
        assertEquals(ids("expense"), detect("只看费用报表呢").orElseThrow().reportIds());
        assertFalse(detect("费用报表").orElseThrow().append());
    }

    @ParameterizedTest
    @CsvSource({
            "应收的也删掉,receivable", "应收报表也删掉,receivable", "把应收报表删掉,receivable",
            "不要费用报表,expense", "去掉销售报表,sales", "排除应收报表,receivable"
    })
    void resolvesScopeRemoveRequests(String message, String expected) {
        PreviewIntentDetector.PreviewIntent intent = detect(message).orElseThrow();
        assertEquals(ids(expected), intent.reportIds());
        assertTrue(intent.remove(), "排除范围必须标记为 remove，服务端才会从上一轮范围中减去");
        assertFalse(intent.append());
    }

    @ParameterizedTest
    @CsvSource({
            "查一下我B公司有哪些能派单,all,B", "查B公司销售报表有哪些可以派单,sales,B",
            "加上B公司费用报表的,expense,B", "A公司销售报表的,sales,A",
            "A公司的销售报表,sales,A", "A 公司销售报表的。,sales,A",
            "公司A销售报表的,sales,A", "我在B公司有吗,all,B", "那B公司有吗,all,B"
    })
    void resolvesExplicitCompanyScope(String message, String expectedType, String expectedCompany) {
        PreviewIntentDetector.PreviewIntent intent = detect(message).orElseThrow();
        assertEquals(ids(expectedType), intent.reportIds());
        assertEquals(expectedCompany, intent.companyCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "不要查销售报表", "销售报表全部帮我派单", "只派销售报表", "销售报表是什么意思",
            "销售报表的规则是什么", "不是销售报表",
            "销售报表不要SO2026002其他都派", "把已勾选的记录帮我派单", "你好", ""
            , "A公司销售报表全部派单", "不要查A公司销售报表", "A公司销售报表的规则是什么",
            "A公司B公司销售报表", "不是A公司，是B公司销售报表"
    })
    void leavesCompoundOrNonPreviewRequestsToModel(String message) {
        assertTrue(detect(message).isEmpty());
    }

    @Test
    void aliasesFromTheCatalogWorkLikeNames() {
        // 历史名称、口语说法都来自目录别名
        assertEquals(ids("receivable"), detect("只看应收发票台账").orElseThrow().reportIds());
        assertEquals(ids("sales"), detect("加上销售台账").orElseThrow().reportIds());
        assertEquals(ids("expense"), detect("报销单呢").orElseThrow().reportIds());
    }

    @Test
    void ambiguousAliasKeepsAllCandidatesForTheResolver() {
        // “客户对账”同时指向两张报表：兜底只负责把原话交给解析服务，由解析服务判定歧义、让用户选择
        PreviewIntentDetector.PreviewIntent intent = detect("客户对账有哪些可以派单").orElseThrow();
        assertEquals(ids("sales|receivable"), intent.reportIds());
        assertEquals("客户对账", intent.reportQuery());
    }

    @Test
    void newReportInTheCatalogIsRecognizedWithoutChangingAnyPattern() {
        // P0-01 验收：新增报表只维护目录数据（名称、别名），识别规则一行不改
        List<CatalogEntry> withPurchase = new ArrayList<>(DEMO);
        withPurchase.add(TestCatalog.purchase());
        TermIndex terms = TestCatalog.terms(withPurchase);
        Set<String> visible = withPurchase.stream().map(CatalogEntry::reportId).collect(Collectors.toSet());
        PreviewIntentDetector.PreviewIntent append = PreviewIntentDetector.detect("加上采购报表", terms, visible).orElseThrow();
        assertEquals(List.of(TestCatalog.PURCHASE), append.reportIds());
        assertTrue(append.append());
        assertEquals(List.of(TestCatalog.PURCHASE), PreviewIntentDetector.detect("采购呢", terms, visible).orElseThrow().reportIds());
        assertEquals(List.of(TestCatalog.PURCHASE), PreviewIntentDetector.detect("PO再查下", terms, visible).orElseThrow().reportIds());
        // 目录里没有这张报表时，同一句话不会被识别
        assertTrue(detect("加上采购报表").isEmpty());
    }

    @Test
    void reportsTheUserCannotSeeAreNotRecognized() {
        // 不可见报表的名称不参与识别：服务端不会替没有权限的用户兜底查询
        Set<String> noReceivable = Set.of(TestCatalog.SALES, TestCatalog.EXPENSE);
        assertTrue(PreviewIntentDetector.detect("只看应收报表", TERMS, noReceivable).isEmpty());
        assertEquals(ids("sales"), PreviewIntentDetector.detect("只看销售报表", TERMS, noReceivable).orElseThrow().reportIds());
    }

    @Test
    void allReportsHasNoQuery() {
        PreviewIntentDetector.PreviewIntent intent = detect("切换到全部报表").orElseThrow();
        assertNull(intent.reportQuery());
        assertTrue(intent.reportIds().isEmpty());
    }
}
