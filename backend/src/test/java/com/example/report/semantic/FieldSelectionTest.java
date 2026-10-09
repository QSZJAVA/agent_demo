package com.example.report.semantic;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.RecordKey;
import com.example.report.rule.*;
import com.example.report.support.TestCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;

/** 字段谓词、目录白名单、精度、空值和原子恢复回归；合成事实不计入真实模型准确率。 */
class FieldSelectionTest {
    @Test void heterogeneousReportFieldsOnlyMatchDeclaredSourcesAndNeverTreatMissingAsNull() {
        var catalog=org.mockito.Mockito.mock(ReportCatalogService.class);
        var sales=org.mockito.Mockito.mock(com.example.report.catalog.CatalogEntry.class);
        var expense=org.mockito.Mockito.mock(com.example.report.catalog.CatalogEntry.class);
        org.mockito.Mockito.when(sales.fields()).thenReturn(List.of(new FieldInfo("productName","string","产品"),new FieldInfo("amount","decimal","金额")));
        org.mockito.Mockito.when(expense.fields()).thenReturn(List.of(new FieldInfo("expenseType","string","费用类型"),new FieldInfo("amount","decimal","金额")));
        org.mockito.Mockito.when(catalog.requireDispatchable(USER1,SALES)).thenReturn(sales);
        org.mockito.Mockito.when(catalog.requireDispatchable(USER1,EXPENSE)).thenReturn(expense);
        var rows=List.of(row(SALES,"s",new FieldFact("productName","string","设备"),new FieldFact("amount","decimal","50")),
                row(EXPENSE,"e",new FieldFact("expenseType","string","交通"),new FieldFact("amount","decimal","60")),
                row(EXPENSE,"n",new FieldFact("expenseType","string",null),new FieldFact("amount","decimal","70")));
        var expenseOnly=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"交通先不选",List.of(),SelectorKind.FIELDS,Quantifier.ALL,
                and(c("expenseType",Comparison.EQ,"交通")));
        assertEquals(List.of(new RecordKey(EXPENSE,"e")),SelectionResolver.apply(rows,List.of(),expenseOnly,catalog,USER1));
        var nullOnly=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"费用类型为空的不选",List.of(),SelectorKind.FIELDS,Quantifier.ALL,
                and(c("expenseType",Comparison.IS_NULL)));
        assertEquals(List.of(new RecordKey(EXPENSE,"n")),SelectionResolver.apply(rows,List.of(),nullOnly,catalog,USER1));
        var impossible=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"产品及费用类型同时满足",List.of(),SelectorKind.FIELDS,Quantifier.ALL,
                and(c("productName",Comparison.EQ,"设备"),c("expenseType",Comparison.EQ,"交通")));
        assertThrows(ApiException.class,()->SelectionResolver.apply(rows,List.of(new RecordKey(SALES,"s")),impossible,catalog,USER1));
    }
    Candidate row(String report,String id,FieldFact... fields) {
        var r=candidate(report,id,"SO"+id,"A","摘要");
        return new Candidate(r.reportId(),r.reportName(),r.recordId(),r.docNo(),r.companyCode(),r.label(),r.amount(),r.date(),r.ruleId(),r.ruleName(),r.ruleVersion(),r.ruleDescription(),r.catalogVersion(),null,List.of(fields));
    }
    FieldCondition c(String field,Comparison operator,String... values) {return new FieldCondition(field,operator,List.of(values),"条件原文");}
    List<ConditionGroup> and(FieldCondition... conditions) {return List.of(new ConditionGroup(List.of(conditions)));}
    @Test void configuredNamesAreGenericAndDecimalThresholdIsStrict() {
        var fields=List.of(new FieldInfo("customMetric","decimal","自定义计量"));
        var test=FieldSelection.compile(and(c("customMetric",Comparison.GT,"96000")),fields);
        assertFalse(test.test(row(SALES,"a",new FieldFact("customMetric","decimal","96000.00"))));
        assertTrue(test.test(row(SALES,"b",new FieldFact("customMetric","decimal","96000.000000000001"))));
        assertThrows(ApiException.class,()->FieldFact.scalar("decimal","1e999999999"));
    }
    @Test void dateTextBooleanAndBoundedOrAndUseDeclaredTypes() {
        var fields=List.of(new FieldInfo("when","date","日期"),new FieldInfo("title","string","标题"),new FieldInfo("enabled","boolean","启用"));
        var groups=List.of(new ConditionGroup(List.of(c("when",Comparison.GTE,"2026-10-01"),c("title",Comparison.CONTAINS,"服务"))),new ConditionGroup(List.of(c("enabled",Comparison.EQ,"true"))));
        var test=FieldSelection.compile(groups,fields);
        assertTrue(test.test(row(SALES,"a",new FieldFact("when","date","2026-10-06"),new FieldFact("title","string","云服务"),new FieldFact("enabled","boolean","false"))));
        assertFalse(test.test(row(SALES,"b",new FieldFact("when","date","2026-09-30"),new FieldFact("title","string","云服务"),new FieldFact("enabled","boolean","false"))));
    }
    @Test void mysqlDecimalPrecisionSurvivesSnapshotAndExactSelection() {
        String precise="12345678901234567890123456789012345.123456789012345678901234567890";
        var fields=List.of(new FieldInfo("amount","decimal","金额"));
        var snapshot=FieldFact.capture(fields,Map.of("amount",new java.math.BigDecimal(precise)));
        assertEquals(precise,snapshot.get(0).value());
        var restored=FieldFact.restore(JsonUtil.toJson(snapshot));
        assertTrue(FieldSelection.compile(and(c("amount",Comparison.EQ,precise)),fields)
                .test(row(SALES,"precise",restored.toArray(FieldFact[]::new))));
        assertThrows(ApiException.class,()->FieldFact.scalar("decimal","9".repeat(66)));
        assertThrows(ApiException.class,()->FieldFact.scalar("decimal","0."+"1".repeat(31)));
    }
    @Test void nullIsNotAnOrdinaryComparisonAndMissingSnapshotFails() {
        var fields=List.of(new FieldInfo("value","decimal","金额"));var row=row(SALES,"a",new FieldFact("value","decimal",null));
        assertFalse(FieldSelection.compile(and(c("value",Comparison.NE,"1")),fields).test(row));
        assertTrue(FieldSelection.compile(and(c("value",Comparison.IS_NULL)),fields).test(row));
        assertThrows(ApiException.class,()->FieldSelection.compile(and(c("value",Comparison.EQ,"1")),fields).test(row(SALES,"x")));
    }
    @Test void unknownFieldAndWrongOperatorOrLiteralFailBeforeEvaluation() {
        var fields=List.of(new FieldInfo("amount","decimal","金额"));
        for(var condition:List.of(c("amount;DROP TABLE x",Comparison.EQ,"1"),c("amount",Comparison.CONTAINS,"1"),c("amount",Comparison.GT,"非数值")))
            assertThrows(ApiException.class,()->FieldSelection.compile(and(condition),fields));
        assertThrows(ApiException.class,()->FieldFact.capture(fields,Map.of()));
    }
    @Test void reportScopedFiltersPreserveOtherReportsAndRoundTripFacts() {
        var catalog=new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties());
        var rows=List.of(row(SALES,"a",new FieldFact("amount","decimal","128000")),row(SALES,"b",new FieldFact("amount","decimal","96000")),row(EXPENSE,"e",new FieldFact("amount","decimal","999999")));
        var selection=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"销售报表金额大于96000不要",List.of("销售报表"),SelectorKind.FIELDS,Quantifier.ALL,
                List.of(new ConditionGroup(List.of(new FieldCondition("amount",Comparison.GT,List.of("96000"),"金额大于96000")))));
        var previous=List.of(new RecordKey(EXPENSE,"e"));
        assertEquals(Set.of(new RecordKey(SALES,"a"),new RecordKey(EXPENSE,"e")),Set.copyOf(SelectionResolver.apply(rows,previous,selection,catalog,USER1)));
        assertEquals(rows.get(0).fields(),FieldFact.restore(JsonUtil.toJson(rows.get(0).fields())));
        var keep=new ScopeChange(Target.RECORDS,Operation.KEEP_ONLY,selection.mentions(),selection.evidence(),selection.reportMentions(),SelectorKind.FIELDS,Quantifier.ALL,selection.conditions());
        assertEquals(Set.of(new RecordKey(SALES,"b"),new RecordKey(EXPENSE,"e")),Set.copyOf(SelectionResolver.apply(rows,previous,keep,catalog,USER1)));
    }
    @Test void fieldProgramWireContractRejectsEmptyOrUnboundedPredicates() {
        String message="查询销售报表，金额大于96000不要";
        var op=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),message,List.of("销售报表"),SelectorKind.FIELDS,Quantifier.ALL,
                List.of(new ConditionGroup(List.of(new FieldCondition("amount",Comparison.GT,List.of("96000"),"金额大于96000")))));
        var intent=new SemanticIntent(1,Action.PREVIEW,List.of(op),List.of(),Clarify.NONE);var codec=new IntentCodec();
        assertEquals(intent,codec.decode(JsonUtil.toJson(intent),message));
        var scoped=new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("销售报表"),message);
        assertDoesNotThrow(()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(scoped,op),List.of(),Clarify.NONE),message));
        assertThrows(ApiException.class,()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),message,List.of(),SelectorKind.FIELDS,Quantifier.ALL)),List.of(),Clarify.NONE),message));
    }
    @Test void unsupportedRequestDoesNotInventUnknownCompanyOrInvalidateReviewedAction() {
        var planner=new SemanticPlanner(new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties()));var state=new DialogueState();state.setEffective(state.getDesired());var before=state.getDesired();
        assertThrows(ApiException.class,()->planner.requireAction(state,new SemanticIntent(1,Action.CLARIFY,List.of(),List.of(),List.of(),List.of("未配置条件"),Clarify.ACTION)));
        assertEquals(before,state.getDesired());assertFalse(state.isUnresolvedCompany());assertFalse(state.isUnresolvedReports());assertTrue(state.isUnresolvedRequest());
        assertDoesNotThrow(()->planner.requireAction(state,new SemanticIntent(1,Action.PREPARE_DISPATCH,List.of(),List.of(),Clarify.NONE)));
        var corrected=new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("SO1"),"SO1不要",List.of("销售报表"),SelectorKind.DOCUMENT,Quantifier.ONE)),List.of(),Clarify.NONE);
        assertDoesNotThrow(()->planner.merge(USER1,state,corrected));assertDoesNotThrow(()->planner.validate(USER1,state));
    }
}
