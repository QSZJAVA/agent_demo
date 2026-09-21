package com.example.report.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportPreviewRequestTest {

    /** 期望值写成 all 或 "sales|receivable" 形式，all 表示全部报表（空列表） */
    private static List<String> types(String expected) {
        return "all".equals(expected) ? List.of() : List.of(expected.split("\\|"));
    }

    @ParameterizedTest
    @CsvSource({
            "我说销售报表,sales", "我说的是销售报表,sales", "我指的是销售,sales",
            "只看应收报表,receivable", "改成费用报表,expense", "切换到全部报表,all",
            "销售报表有啥能派单的,sales", "销售报表有哪些可以派单的,sales",
            "销售报表有哪些可以派单的？,sales", "查一下费用报表有哪些可以派单,expense",
            "帮我查询应收报表的待派单记录,receivable", "销售报表,sales", "全部报表,all",
            "只看销售报表吧,sales", " SALES ,sales", "查一下我B公司有哪些能派单的,all",
            // 一次说多张报表
            "销售报表和应收报表有哪些可以派单,sales|receivable",
            "查一下应收、费用报表有哪些可以派单,receivable|expense",
            "应收+费用,receivable|expense",
            // 类型顺序按用户说出的先后保持
            "费用报表和销售报表,expense|sales"
    })
    void resolvesExplicitPreviewScope(String message, String expected) {
        assertEquals(types(expected), ReportPreviewRequest.resolve(message).orElseThrow().reportTypes());
    }

    @ParameterizedTest
    @CsvSource({
            "加上费用报表的,expense", "再加上应收报表,receivable", "还要看费用报表的,expense",
            "顺便查一下销售报表的,sales", "另外加上应收报表和费用报表,receivable|expense",
            // 追问式追加：在刚才的范围上再看看另一张报表
            "费用报表呢,expense", "那应收呢,receivable", "费用报表吗,expense", "销售呢,sales"
    })
    void resolvesScopeAppendRequests(String message, String expected) {
        ReportPreviewRequest request = ReportPreviewRequest.resolve(message).orElseThrow();
        assertEquals(types(expected), request.reportTypes());
        assertTrue(request.append(), "追加范围必须标记为 append，服务端才会与上一轮范围合并");
    }

    @ParameterizedTest
    @CsvSource({
            // 口语化追问：这类说法以前匹配不上任何模式，服务端不兜底刷新预览，模型就容易只说一句"已重查"却什么也没查
            "应收报表再查下,receivable", "应收报表再查一下,receivable",
            "应收报表重查一下,receivable", "销售报表再看看,sales",
            "费用报表刷新一下,expense", "应收报表查一下,receivable",
            "B公司应收报表再查下,receivable"
    })
    void resolvesFollowUpReQueryRequests(String message, String expected) {
        ReportPreviewRequest request = ReportPreviewRequest.resolve(message).orElseThrow();
        assertEquals(types(expected), request.reportTypes());
        assertFalse(request.append(), "重新查这张报表是替换范围，不是追加");
        assertFalse(request.remove());
    }

    @Test
    void marksOnlyAppendRequestsAsAppend() {
        assertFalse(ReportPreviewRequest.resolve("我说销售报表").orElseThrow().append());
        assertFalse(ReportPreviewRequest.resolve("查一下应收和费用报表有哪些能派单").orElseThrow().append());
        assertEquals(List.of("receivable", "expense"),
                ReportPreviewRequest.resolve("查一下应收和费用报表有哪些能派单").orElseThrow().reportTypes());
        // 明确要求换一张报表时是替换，不是追加
        assertFalse(ReportPreviewRequest.resolve("只看费用报表呢").orElseThrow().append());
        assertEquals(List.of("expense"), ReportPreviewRequest.resolve("只看费用报表呢").orElseThrow().reportTypes());
        assertFalse(ReportPreviewRequest.resolve("费用报表").orElseThrow().append());
    }

    @ParameterizedTest
    @CsvSource({
            "应收的也删掉,receivable", "应收报表也删掉,receivable", "把应收报表删掉,receivable",
            "不要费用报表,expense", "去掉销售报表,sales", "排除应收报表,receivable"
    })
    void resolvesScopeRemoveRequests(String message, String expected) {
        ReportPreviewRequest request = ReportPreviewRequest.resolve(message).orElseThrow();
        assertEquals(types(expected), request.reportTypes());
        assertTrue(request.remove(), "排除范围必须标记为 remove，服务端才会从上一轮范围中减去");
        assertFalse(request.append());
    }

    @ParameterizedTest
    @CsvSource({
            "查一下我B公司有哪些能派单,all,B", "查B公司销售报表有哪些可以派单,sales,B",
            "加上B公司费用报表的,expense,B"
    })
    void resolvesExplicitCompanyScope(String message, String expectedType, String expectedCompany) {
        ReportPreviewRequest request = ReportPreviewRequest.resolve(message).orElseThrow();
        assertEquals(types(expectedType), request.reportTypes());
        assertEquals(expectedCompany, request.companyCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "不要查销售报表", "销售报表全部帮我派单", "只派销售报表", "销售报表是什么意思",
            "销售报表的规则是什么", "不是销售报表",
            "销售报表不要SO2026002其他都派", "把已勾选的记录帮我派单", "你好", ""
    })
    void leavesCompoundOrNonPreviewRequestsToModel(String message) {
        assertTrue(ReportPreviewRequest.resolve(message).isEmpty());
    }
}
