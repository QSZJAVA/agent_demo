package com.example.report.analysis;

import com.example.report.assistant.*;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import com.example.report.workspace.QueryAnalysis;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 分析图严格契约回归；只验证纯程序边界，不替代真实模型规划或业务接口验收。 */
class AnalysisContractTest {
    static final AnalysisContract.Request REQUEST=new AnalysisContract.Request("A","rpt-sales-order","按产品统计再展开前两组",null);
    static final List<FieldInfo> FIELDS=List.of(new FieldInfo("amount","decimal","金额"),new FieldInfo("productName","string","产品"),new FieldInfo("date","date","日期"));
    static AnalysisContract.Step query(String company){return new AnalysisContract.Step("QUERY",null,null,new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("rpt-sales-order"),company,List.of(),null,false,1,20,null),null,null,null);}
    static AnalysisContract.Step group(int source){return new AnalysisContract.Step("GROUP",source,null,null,new QueryAnalysis.Request(List.of("productName"),null,"NONE",null),null,null);}
    static AnalysisContract.Step rank(int source){return new AnalysisContract.Step("RANK",source,null,null,null,new AnalysisContract.Rank("sum",true,2),null);}
    static AnalysisContract.Plan plan(AnalysisContract.Step...steps){return new AnalysisContract.Plan("EXECUTE","先统计再展开",null,List.of(steps));}
    @Test void acceptsBoundedAggregateRankAndSameSourceDrill(){assertDoesNotThrow(()->AnalysisContract.validate(REQUEST,plan(query("A"),group(0),rank(1),new AnalysisContract.Step("DRILLDOWN",0,2,null,null,null,null)),FIELDS));}
    @Test void rejectsCrossCompany(){assertThrows(ApiException.class,()->AnalysisContract.validate(REQUEST,plan(query("B")),FIELDS));}
    @Test void rejectsFutureReference(){assertThrows(ApiException.class,()->AnalysisContract.validate(REQUEST,plan(query("A"),group(2)),FIELDS));}
    @Test void rejectsSelfReference(){assertThrows(ApiException.class,()->AnalysisContract.validate(REQUEST,plan(query("A"),group(1)),FIELDS));}
    @Test void rejectsGroupFromAnotherBase(){assertThrows(ApiException.class,()->AnalysisContract.validate(REQUEST,plan(query("A"),query("A"),group(0),rank(2),new AnalysisContract.Step("DRILLDOWN",1,3,null,null,null,null)),FIELDS));}
    @Test void rejectsMoreThanTwoBaseQueries(){assertThrows(ApiException.class,()->AnalysisContract.validate(REQUEST,plan(query("A"),query("A"),query("A")),FIELDS));}
    @Test void rejectsMoreThanSixSteps(){assertThrows(ApiException.class,()->plan(query("A"),query("A"),group(0),group(0),group(0),group(0),group(0)));}
    @Test void rejectsUndeclaredGroupField(){var step=new AnalysisContract.Step("GROUP",0,null,null,new QueryAnalysis.Request(List.of("customerId"),null,"NONE",null),null,null);assertThrows(ApiException.class,()->AnalysisContract.validate(REQUEST,plan(query("A"),step),FIELDS));}
    @Test void rejectsUnknownTool(){assertThrows(ApiException.class,()->new AnalysisContract.Step("dispatch",null,null,null,null,null,null));}
    @Test void rejectsSnapshotWithoutExplicitReference(){assertThrows(ApiException.class,()->AnalysisContract.validate(REQUEST,plan(new AnalysisContract.Step("SNAPSHOT",null,null,null,null,null,null)),FIELDS));}
    @Test void rejectsClarificationWithExecutableSteps(){assertThrows(ApiException.class,()->new AnalysisContract.Plan("CLARIFY","确认范围","哪家？",List.of(query("A"))));}
    @Test void rejectsExtraFieldAndDuplicateKeys(){String json=JsonUtil.toJson(REQUEST);assertThrows(ApiException.class,()->AnalysisContract.decode(json.replaceFirst("\\{","{\"tenantId\":\"OTHER\","),AnalysisContract.Request.class));assertThrows(ApiException.class,()->AnalysisContract.decode(json.replaceFirst("\\{","{\"companyCode\":\"B\","),AnalysisContract.Request.class));}
    @Test void rejectsNumericStringCoercionAndNullPrimitive(){assertThrows(ApiException.class,()->AnalysisContract.decode("{\"field\":\"amount\",\"descending\":true,\"count\":\"2\"}",AnalysisContract.Rank.class));assertThrows(ApiException.class,()->AnalysisContract.decode("{\"field\":\"amount\",\"descending\":null,\"count\":2}",AnalysisContract.Rank.class));}
    @Test void rejectsUnusedArguments(){assertThrows(ApiException.class,()->new AnalysisContract.Step("KNOWLEDGE",null,null,null,new QueryAnalysis.Request(List.of(),null,"NONE",null),null,"口径"));}
    @Test void forbidsCredentialTextBeforeAnyModelCall(){assertThrows(ApiException.class,()->new AnalysisContract.Request("A","rpt-sales-order","password=not-a-real-fixture",null));}
    @Test void rejectsInvalidNestedCitationAsProtocolError(){assertThrows(ApiException.class,()->AnalysisContract.decode("{\"status\":\"ANSWERED\",\"claims\":[{\"text\":\"结论\",\"citations\":[null]}],\"limitations\":[]}",AnalysisEvidence.Answer.class));}
    @Test void validatesTypedReviewWithoutWeakeningDuplicateKeyProtection(){var accepted=AnalysisContract.decode("{\"approved\":true,\"reason\":\"只读图及来源复核通过\"}",AnalysisContract.Review.class);assertTrue(accepted.approved());
        assertThrows(ApiException.class,()->AnalysisContract.decode("{\"approved\":false,\"approved\":true,\"reason\":\"重复判断\"}",AnalysisContract.Review.class));}
    @Test void rankMustUsePrimaryInputAndExplainsRepair(){var failure=assertThrows(ApiException.class,()->new AnalysisContract.Step("RANK",null,1,null,null,new AnalysisContract.Rank("sum",true,2),null));assertTrue(failure.getMessage().contains("RANK.source"));
        assertThrows(ApiException.class,()->AnalysisContract.validate(REQUEST,plan(query("A"),group(0),new AnalysisContract.Step("RANK",1,null,null,null,new AnalysisContract.Rank("sum(amount)",true,2),null)),FIELDS));}
    @Test void knowledgeDoesNotRequireADataInputOrDictionaryHit(){assertDoesNotThrow(()->AnalysisContract.validate(REQUEST,plan(new AnalysisContract.Step("KNOWLEDGE",null,null,null,null,null,"缺少依据的制度问题")),List.of()));}
    @Test void reviewLineageTracesRankingThroughGroupBackToOriginalRows(){var plan=plan(query("A"),group(0),rank(1),new AnalysisContract.Step("DRILLDOWN",0,2,null,null,null,null));AnalysisContract.validate(REQUEST,plan,FIELDS);
        var path=AnalysisContract.lineage(plan).get(0);assertEquals(0,path.get("rowsStep"));assertEquals(1,path.get("groupStep"));assertEquals(2,path.get("rankStep"));}
}
