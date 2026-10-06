package com.example.report.semantic;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.RecordKey;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证报表内的记录排除不改变查询范围，覆盖协议证据、跨报表重名、权限及失败原子性；不调用真实模型。 */
class ScopedRecordSelectionTest {
    final ReportCatalogService catalog=new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties());
    final SemanticPlanner planner=new SemanticPlanner(catalog);
    final IntentCodec codec=new IntentCodec();
    final String message="应收报表不要天津某某贸易有限公司的";
    final ScopeChange exclusion=new ScopeChange(Target.RECORDS,Operation.ADD,List.of("天津某某贸易有限公司"),message,List.of("应收报表"));

    @Test void recordQualifierCoversReportWithoutReplacingAllReports() {
        var intent=new SemanticIntent(1,Action.PREVIEW,List.of(exclusion),List.of(),Clarify.NONE);
        assertEquals(intent,codec.decode(JsonUtil.toJson(intent),message));
        var state=new DialogueState();
        var scope=state.getDesired();
        planner.requireCoverage(state,intent,planner.mentions(USER1,message));
        planner.merge(USER1,state,intent);
        planner.validate(USER1,state);
        assertEquals(scope,state.getDesired());
        assertTrue(state.getDesired().allReports());
        var missing=new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.RECORDS,Operation.ADD,
                exclusion.mentions(),message)),List.of(),Clarify.NONE);
        assertFalse(SemanticPlanner.hasReportCoverage(missing,List.of("应收报表")));
    }

    @Test void qualificationResolvesSameLabelAcrossReportsButNeverArbitrarilyWithinOneReport() {
        var rows=List.of(candidate(SALES,"7","SO7","A","天津某某贸易有限公司"),
                candidate(RECEIVABLE,"7","INV7","A","天津某某贸易有限公司"),
                candidate(RECEIVABLE,"8","INV8","A","北京某某咨询有限公司"));
        var previous=List.of(new RecordKey(SALES,"7"));
        var selected=SelectionResolver.apply(rows,previous,exclusion,catalog,USER1);
        assertEquals(List.of(new RecordKey(SALES,"7"),new RecordKey(RECEIVABLE,"7")),selected);
        var clear=new ScopeChange(Target.RECORDS,Operation.CLEAR,List.of(),"恢复应收报表全部记录",List.of("应收报表"));
        assertEquals(previous,SelectionResolver.apply(rows,selected,clear,catalog,USER1));
        assertThrows(ApiException.class,()->SelectionResolver.apply(rows,previous,exclusion.change()));
        var ambiguous=List.of(rows.get(1),candidate(RECEIVABLE,"9","INV9","A","天津某某贸易有限公司"));
        assertThrows(ApiException.class,()->SelectionResolver.apply(ambiguous,previous,exclusion,catalog,USER1));
        assertEquals(List.of(new RecordKey(SALES,"7")),previous);
    }

    @Test void unavailableOrUnauthorizedQualifierCannotBroadenPreview() {
        var salesOnly=List.of(candidate(SALES,"7","SO7","A","天津某某贸易有限公司"));
        assertThrows(ApiException.class,()->SelectionResolver.apply(salesOnly,List.of(),exclusion,catalog,USER1));
        var receivable=List.of(candidate(RECEIVABLE,"7","INV7","B","天津某某贸易有限公司"));
        assertThrows(ApiException.class,()->SelectionResolver.apply(receivable,List.of(),exclusion,catalog,USER3));
    }

    @Test void qualifierRequiresLocalEvidenceAndCannotAppearOnScopeChanges() {
        var intent=new SemanticIntent(1,Action.PREVIEW,List.of(exclusion),List.of(),Clarify.NONE);
        var json=JsonUtil.toJson(intent);
        assertThrows(ApiException.class,()->codec.decode(json.replace("\"reportMentions\":[\"应收报表\"]","\"reportMentions\":[\"费用报表\"]"),message));
        assertThrows(ApiException.class,()->codec.decode(json.replace("\"target\":\"RECORDS\"","\"target\":\"REPORTS\""),message));
        assertThrows(ApiException.class,()->codec.decode(json.replace(",\"reportMentions\":[\"应收报表\"]",""),message));
    }
}
