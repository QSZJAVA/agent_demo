package com.example.report.analysis;

import com.example.report.common.ApiException;
import com.example.report.assistant.BusinessResult;
import com.example.report.assistant.BusinessQueryEngine;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.workspace.QueryAnalysis;
import com.example.report.workspace.QueryComparison;
import com.example.report.workspace.QuerySnapshotStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 分组排名及同一快照展开的确定性回归，覆盖精确金额、NULL、并列、币种和日期边界。 */
class AnalysisMathTest {
    static Map<String,Object> group(Object value,String amount,String currency,String period){var map=new LinkedHashMap<String,Object>();var dimension=new LinkedHashMap<String,Object>();dimension.put("productName",value);map.put("dimensions",dimension);map.put("currency",currency);map.put("period",period);map.put("count",1);map.put("sum",amount);map.put("average",amount);return map;}
    static Map<String,Object> row(Object name,String currency,String date){var map=new LinkedHashMap<String,Object>();map.put("productName",name);map.put("currency",currency);map.put("date",date);return map;}
    @Test void ranksExactAmountsBeyondDoublePrecision(){var ranked=AnalysisMath.rankGroups(List.of(group("low","90071992547409.92","CNY",null),group("high","90071992547409.93","CNY",null)),new AnalysisContract.Rank("sum",true,1));assertEquals("high",((Map<?,?>)ranked.get(0).get("dimensions")).get("productName"));}
    @Test void tiesAreStableAcrossInputOrder(){var a=group("Alpha","10","CNY",null);var b=group("alpha ","10.00","CNY",null);var rank=new AnalysisContract.Rank("sum",true,2);assertEquals(AnalysisMath.rankGroups(List.of(a,b),rank),AnalysisMath.rankGroups(List.of(b,a),rank));}
    @Test void amountRankingRejectsMixedCurrency(){assertThrows(ApiException.class,()->AnalysisMath.rankGroups(List.of(group("a","100","CNY",null),group("b","100","USD",null)),new AnalysisContract.Rank("sum",true,1)));}
    @Test void countRankingCanCompareCountsAcrossCurrencies(){assertEquals(2,AnalysisMath.rankGroups(List.of(group("a","100","CNY",null),group("b","100","USD",null)),new AnalysisContract.Rank("count",true,2)).size());}
    @Test void rejectsInsufficientNonNullGroups(){assertThrows(ApiException.class,()->AnalysisMath.rankGroups(List.of(group("a",null,"CNY",null)),new AnalysisContract.Rank("sum",true,1)));}
    @Test void drillPreservesCaseAndTrailingSpace(){var rows=List.of(row("Alpha","CNY","2026-09-01"),row("alpha ","CNY","2026-09-01"));var found=AnalysisMath.drill(rows,new QueryAnalysis.Request(List.of("productName"),null,"NONE",null),List.of(group("alpha ","1","CNY",null)));assertEquals(List.of(rows.get(1)),found);}
    @Test void drillSeparatesNullAndTextNull(){var rows=List.of(row(null,"CNY",null),row("null","CNY",null));assertEquals(List.of(rows.get(0)),AnalysisMath.drill(rows,new QueryAnalysis.Request(List.of("productName"),null,"NONE",null),List.of(group(null,"1","CNY",null))));}
    @Test void drillUsesMatchingCurrencyAndMonth(){var rows=List.of(row("a","CNY","2026-09-30"),row("a","USD","2026-09-30"),row("a","CNY","2026-10-01"));assertEquals(List.of(rows.get(0)),AnalysisMath.drill(rows,new QueryAnalysis.Request(List.of("productName"),"date","MONTH",null),List.of(group("a","1","CNY","2026-09"))));}
    @Test void rejectsUnmatchedDimensionShape(){assertThrows(ApiException.class,()->AnalysisMath.drill(List.of(row("a","CNY",null)),new QueryAnalysis.Request(List.of(),null,"NONE",null),List.of(group("a","1","CNY",null))));}
    @Test void readableEvidenceUsesTypedSummaryWithoutLosingPrecisionOrQuery(){var query=AnalysisContractTest.query("A").query();var facts=new LinkedHashMap<String,Object>();facts.put("kind","ROWS");facts.put("count",120);facts.put("displayedCount",20);facts.put("allRowsDisplayed",false);facts.put("query",query);
        facts.put("summary",new BusinessResult.Summary(120,Map.of("CNY","90071992547409.93"),Map.of(),Map.of(),Map.of(),0));facts.put("selectedGroupCount",2);facts.put("displayStep",4);
        String text=AnalysisEngine.readableFacts("DRILLDOWN",facts);assertTrue(text.contains("90071992547409.93 CNY"));assertTrue(text.contains("完整结果共120条记录"));assertTrue(text.contains("\"companyCode\":\"A\""));assertTrue(text.contains("全部行已展示：false"));assertTrue(text.contains("按已选2个分组"));
        var source=new AnalysisEvidence.Source("view","view","DATA","明细结果","v1","A","当前时间",text,0);
        var supported=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("界面第4步可每页20条查看完整明细。",List.of("view"))),List.of());
        assertDoesNotThrow(()->AnalysisEvidence.resolve(supported,List.of(source)));
        var internalIndex=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("界面第3步可每页20条查看完整明细。",List.of("view"))),List.of());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(internalIndex,List.of(source)));
    }
    @Test void comparisonEvidenceSuppliesBothSignedDeltaAndExplicitDecreasedMagnitude(){
        String text=comparisonText(List.of(amount("240.18","CNY"),amount("100.23","CNY"),amount("50.08","CNY")),List.of(amount("250.41","CNY")));
        assertTrue(text.contains("金额差额：-140.08 CNY"));assertTrue(text.contains("金额比之前查询集合减少140.08 CNY"));
        assertTrue(text.contains("记录数差额：-2 条"));assertTrue(text.contains("记录数比之前查询集合减少2 条"));
        assertTrue(text.contains("条件不同只描述集合差异"));assertTrue(text.contains("不是跨查询全局事务"));
        assertCited(text,"之后集合比之前集合减少2条；金额差额为-140.08 CNY，即少140.08 CNY。");
        assertThrows(ApiException.class,()->assertCited(text,"金额少140.09 CNY。"));
        // 只有旧的负差额原文并不支持正幅度；本次新增事实不能变成金额校验的全局abs豁免。
        assertThrows(ApiException.class,()->assertCited("之后减之前的金额差额为-140.08 CNY。","金额少140.08 CNY。"));
    }
    @Test void comparisonEvidenceKeepsIncreasingAndDecreasingCurrenciesSeparate(){
        String text=comparisonText(List.of(amount("-20.00","CNY"),amount("100.00","USD")),List.of(amount("-10.00","CNY"),amount("99.00","USD")));
        assertTrue(text.contains("金额比之前查询集合增加10.00 CNY"));assertTrue(text.contains("金额比之前查询集合减少1.00 USD"));
        assertTrue(text.contains("记录数与之前查询集合相同"));assertCited(text,"CNY增加10元，USD减少1元，记录数差额为0条。");
        assertThrows(ApiException.class,()->assertCited(text,"跨币种净增加9元。"));
    }
    @Test void comparisonEvidenceTreatsZeroAsUnchangedWithoutInventingDirection(){
        String text=comparisonText(List.of(amount("9.00","CNY")),List.of(amount("9","CNY")));
        assertTrue(text.contains("金额差额：0.00 CNY"));assertTrue(text.contains("金额与之前查询集合相同，差额为0 CNY"));
        assertFalse(text.contains("增加"));assertFalse(text.contains("减少"));assertCited(text,"金额与记录数差额均为0。");
    }
    @Test void comparisonEvidencePreservesMagnitudeBeyondBinaryFloatingPointPrecision(){
        String text=comparisonText(List.of(amount("90071992547409.93","CNY")),List.of(amount("0.01","CNY")));
        assertTrue(text.contains("金额差额：-90071992547409.92 CNY"));assertTrue(text.contains("减少90071992547409.92 CNY"));
        assertCited(text,"金额差额为-90071992547409.92 CNY，即减少90071992547409.92 CNY。");
        assertThrows(ApiException.class,()->assertCited(text,"减少90071992547409.93 CNY。"));
    }
    /** 从独立给定的完整行集合执行共同比较，再检验进入模型的引用正文；预期值不由相同格式化器回算。 */
    private static String comparisonText(List<Map<String,Object>> before,List<Map<String,Object>> after){
        var facts=new LinkedHashMap<>(QueryComparison.compare(comparisonRun(before),before,comparisonRun(after),after));facts.put("kind","COMPARISON");
        return AnalysisEngine.readableFacts("COMPARE",facts);
    }
    private static Map<String,Object> amount(String amount,String currency){return Map.of("rowKey",currency+":"+amount,"reportId","rpt-sales-order","companyCode","A","amount",amount,"currency",currency);}
    private static QuerySnapshotStore.Run comparisonRun(List<Map<String,Object>> rows){
        var query=AnalysisContractTest.query("A").query();var columns=List.of(new FieldInfo("amount","decimal","金额"),new FieldInfo("currency","string","币种"));
        var calculated=BusinessQueryEngine.execute(query,columns,rows,"独立给定的比较测试事实");
        var result=new BusinessResult(query,"2026-10-13T00:00:00Z",calculated.source(),columns,calculated.rows(),calculated.total(),calculated.summary());
        return new QuerySnapshotStore.Run(result,"same-definition",Set.of("A"));
    }
    private static void assertCited(String text,String claim){
        var source=new AnalysisEvidence.Source("comparison","comparison","DATA","查询集合比较","v1","A","当前时间",text,0);
        AnalysisEvidence.resolve(new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim(claim,List.of(source.id()))),List.of()),List.of(source));
    }
}
