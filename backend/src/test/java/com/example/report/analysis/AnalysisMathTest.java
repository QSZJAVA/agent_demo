package com.example.report.analysis;

import com.example.report.common.ApiException;
import com.example.report.assistant.BusinessResult;
import com.example.report.workspace.QueryAnalysis;
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
        var source=new AnalysisEvidence.Source("view","DATA","明细结果","v1","A","当前时间",text);
        var supported=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("界面第4步可每页20条查看完整明细。",List.of("view"))),List.of());
        assertDoesNotThrow(()->AnalysisEvidence.resolve(supported,List.of(source)));
        var internalIndex=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("界面第3步可每页20条查看完整明细。",List.of("view"))),List.of());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(internalIndex,List.of(source)));
    }
}
