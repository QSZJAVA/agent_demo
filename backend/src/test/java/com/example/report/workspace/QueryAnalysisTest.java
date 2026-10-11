package com.example.report.workspace;

import com.example.report.assistant.*;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立构造带已知结果的事实集，验证精确金额、缺值、期间、排名和对比，不把同一计算器输出当作预期。 */
class QueryAnalysisTest {
    static final List<FieldInfo> COLUMNS=List.of(new FieldInfo("amount","decimal","金额"),new FieldInfo("currency","string","币种"),new FieldInfo("date","date","业务日期"),new FieldInfo("label","string","名称"),new FieldInfo("companyCode","string","公司"));
    static BusinessQuery query(){return new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),"A",List.of(),null,false,1,2,null);}
    static Map<String,Object> row(String id,String amount,String currency,String date,String label){var row=new LinkedHashMap<String,Object>();row.put("rowKey",id);row.put("reportId","r");row.put("companyCode","A");row.put("amount",amount);row.put("currency",currency);row.put("date",date);row.put("label",label);return row;}
    static QuerySnapshotStore.Run run(List<Map<String,Object>> rows){return run(query(),rows);}
    static QuerySnapshotStore.Run run(BusinessQuery q,List<Map<String,Object>> rows){var r=BusinessQueryEngine.execute(q,COLUMNS,rows,"已知测试事实");return new QuerySnapshotStore.Run(new BusinessResult(q,"2026-10-11T00:00:00Z",r.source(),COLUMNS,r.rows(),r.total(),r.summary(),new BusinessResult.Snapshot("test","2026-10-12T00:00:00Z","COMPLETE","LOCAL_REPEATABLE_READ","hash",rows.size())),"definition",Set.of("A"));}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> groups(Map<String,Object> result){return (List<Map<String,Object>>)result.get("groups");}
    @Test void exactCurrencySumsAndMeansUseAllRowsWithSeparateNulls(){var rows=List.of(row("1","90071992547409.93","CNY",null,null),row("2","0.01","CNY",null,null),row("3",null,"CNY",null,null),row("4","7.00","USD",null,"未提供"),row("5","9.00",null,null,"未知币种"));var result=QueryAnalysis.analyze(run(rows),rows,new QueryAnalysis.Request(List.of("label"),null,"NONE",null));var cny=groups(result).stream().filter(g->"CNY".equals(g.get("currency"))).findFirst().orElseThrow();assertEquals("90071992547409.94",cny.get("sum"));assertEquals("45035996273704.97",cny.get("average"));assertEquals(3,cny.get("count"));assertEquals(1,cny.get("missingAmountCount"));assertEquals(1,result.get("unclassifiedAmountCount"));assertEquals(3,groups(result).size());}
    @Test void rankUsesCompleteSetStableTiesAndNeverNullAsLargest(){var rows=List.of(row("r:2","100","CNY",null,"B"),row("r:1","100.0","CNY",null,"A"),row("r:3",null,"CNY",null,"C"),row("r:4","99.99","CNY",null,"D"));var result=QueryAnalysis.rank(run(rows),rows,new QueryAnalysis.Rank("amount",true,3));assertEquals(List.of("r:1","r:2","r:4"),result.stream().map(r->r.get("rowKey")).toList());assertThrows(ApiException.class,()->QueryAnalysis.rank(run(rows),rows,new QueryAnalysis.Rank("amount",true,4)));}
    @Test void currencyMixAndUndeclaredCurrencyCannotBeRankedByAmount(){for(String currency:Arrays.asList("USD",null)){var rows=List.of(row("1","1","CNY",null,"A"),row("2","2",currency,null,"B"));assertThrows(ApiException.class,()->QueryAnalysis.rank(run(rows),rows,new QueryAnalysis.Rank("amount",true,1)));}}
    @Test void periodGrowthUsesKnownCompleteClosedMonths(){var rows=List.of(row("1","120","CNY","2026-09-02","A"),row("2","100","CNY","2026-08-02","A"),row("3","60","CNY","2025-09-02","A"));var result=QueryAnalysis.analyze(run(rows),rows,new QueryAnalysis.Request(List.of(),"date","MONTH","2026-09"));var comparison=(Map<?,?>)result.get("comparison");assertEquals(true,comparison.get("available"));var values=(Map<?,?>)((List<?>)comparison.get("rows")).get(0);assertEquals("20",values.get("monthOverMonthPercent"));assertEquals("100",values.get("yearOverYearPercent"));}
    @Test void incompleteCurrentOrFilteredPeriodsHaveNoGrowthRate(){var rows=List.of(row("1","120","CNY","2026-09-02","A"));var q=query();q=new BusinessQuery(q.domain(),q.view(),q.reportIds(),q.companyCode(),List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("date","GTE",List.of("2026-09-01"))))),null,false,1,2,null);var result=QueryAnalysis.analyze(run(q,rows),rows,new QueryAnalysis.Request(List.of(),"date","MONTH","2026-09"));assertEquals(false,((Map<?,?>)result.get("comparison")).get("available"));result=QueryAnalysis.analyze(run(rows),rows,new QueryAnalysis.Request(List.of(),"date","MONTH","2026-10"));assertEquals(false,((Map<?,?>)result.get("comparison")).get("available"));}
    @Test void missingDateAmountOrCurrencyPreventsClaimingCompletePeriodGrowth(){
        for(var missing:List.of(row("2","10","CNY",null,"B"),row("2",null,"CNY","2026-08-10","B"),row("2","10",null,"2025-09-10","B"))){
            var rows=List.of(row("1","120","CNY","2026-09-02","A"),missing);
            var result=QueryAnalysis.analyze(run(rows),rows,new QueryAnalysis.Request(List.of(),"date","MONTH","2026-09"));
            assertEquals(false,((Map<?,?>)result.get("comparison")).get("available"));
        }
    }
    @Test void zeroOrNegativeBaselineHasNoGrowthWhileValidMonthRemainsExact(){
        for(String baseline:List.of("0","-20")){
            var rows=List.of(row("1","120","CNY","2026-09-02","A"),row("2",baseline,"CNY","2026-08-02","A"),row("3","60","CNY","2025-09-02","A"));
            var result=QueryAnalysis.analyze(run(rows),rows,new QueryAnalysis.Request(List.of(),"date","MONTH","2026-09"));
            var comparison=(Map<?,?>)result.get("comparison");assertEquals(true,comparison.get("available"));
            var value=(Map<?,?>)((List<?>)comparison.get("rows")).get(0);assertNull(value.get("monthOverMonthPercent"));assertEquals("100",value.get("yearOverYearPercent"));
        }
    }
    @Test void lastMonthReplacesOnlyExplicitDateFieldIncludingLeapYear(){var q=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),"A",List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GTE",List.of("20")),new BusinessQuery.Filter("date","EQ",List.of("2020-01-01"))))),null,false,1,20,null);var resolved=QueryWorkspaceService.lastMonth(q,"date",LocalDate.of(2024,3,1));var conditions=resolved.conditions().get(0).allOf();assertEquals(q.conditions().get(0).allOf().get(0),conditions.get(0));assertEquals(List.of("2024-02-01"),conditions.get(1).values());assertEquals(List.of("2024-02-29"),conditions.get(2).values());}
    @Test void comparisonDistinguishesNullZeroAndNumericScale(){var old=List.of(row("1","1.00","CNY",null,"A"),row("2",null,"CNY",null,"B"),row("3","2","CNY",null,"C"));var next=List.of(row("1","1.0","CNY",null,"A"),row("2","0.00","CNY",null,"B"),row("4","3","CNY",null,"D"));var diff=QueryComparison.compare(run(old),old,run(next),next);assertEquals(1,diff.get("changed"));assertEquals(1,diff.get("added"));assertEquals(1,diff.get("removed"));assertEquals(true,diff.get("sameDefinition"));assertEquals("1.00",((Map<?,?>)diff.get("amountDeltaByCurrency")).get("CNY"));}
    @Test void comparisonDoesNotTreatDifferentFiltersAsGrowth(){var rows=List.of(row("1","20","CNY",null,"A"));var q=query();var different=new BusinessQuery(q.domain(),q.view(),q.reportIds(),q.companyCode(),List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("0"))))),null,false,1,2,null);assertEquals(false,QueryComparison.compare(run(rows),rows,run(different,rows),rows).get("sameDefinition"));}
    @Test void csvPreservesHighPrecisionAndNeutralizesFormulaCells(){var rows=List.of(row("1","90071992547409.93","CNY",null,"=1+1,\"text\"\r\nnext"));var csv=new QueryCsv(run(rows).result());rows.forEach(csv::add);var text=new String(csv.bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(text.startsWith("\ufeff"));assertTrue(text.contains("\"'90071992547409.93\""));assertTrue(text.contains("\"'=1+1,\"\"text\"\"\r\nnext\""));for(String formula:List.of("=1+1","+1","-1","@SUM(A1)","\t=cmd","\r=cmd"))assertTrue(QueryCsv.cell(formula).startsWith("'"));assertEquals("",QueryCsv.cell(null));}
    @Test void csvHeadersAndSourceMetadataUseTheSameFormulaProtection(){
        var original=run(List.of(row("1","1.01","CNY",null,"A"))).result();
        var result=new BusinessResult(original.query(),original.observedAt(),"=1+1",List.of(new FieldInfo("amount","decimal","@SUM(A1)")),original.rows(),original.total(),original.summary(),original.snapshot());
        String text=new String(new QueryCsv(result).bytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("\"'=1+1\""));assertTrue(text.contains("\"'@SUM(A1) [amount]\""));
        assertFalse(text.contains("\"=1+1\""));assertFalse(text.contains("\"@SUM(A1) [amount]\""));
    }
}
