package com.example.report.semantic;

import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.dispatch.RecordKey;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;

class SemanticProtocolTest {
    final IntentCodec codec=new IntentCodec();
    static Change change(Operation operation,String... mentions) { return new Change(operation,List.of(mentions),String.join("、",mentions)); }
    static SemanticIntent intent(Change company,Change reports) { return new SemanticIntent(2,Action.PREVIEW,company,reports,Change.keep(),Clarify.NONE); }
    @Test void rejectsInventedGroundingExtraFieldsAndExecution() {
        var valid=intent(change(Operation.REPLACE,"A公司"),change(Operation.REPLACE,"销售报表"));
        String json=JsonUtil.toJson(valid);
        assertEquals(valid,codec.decode(json,"只查 A公司 销售报表"));
        assertThrows(ApiException.class,()->codec.decode(json,"只查 B公司 销售报表"));
        assertThrows(ApiException.class,()->codec.decode(json.replace("PREVIEW","EXECUTE"),"A公司销售报表"));
        assertThrows(ApiException.class,()->codec.decode(json.replace("\"version\":2","\"version\":2,\"userId\":\"admin\""),"A公司销售报表"));
        assertThrows(ApiException.class,()->codec.decode(json+" {}","A公司销售报表"));
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"version\":2}","{\"version\":null}","not json"})
    void invalidOutputsRequireClarification(String json) { assertThrows(ApiException.class,()->codec.decode(json,"查询")); }
    @Test void keepClearAndUnsupportedCompanyOperationsAreDistinct() {
        assertThrows(ApiException.class,()->codec.validate(intent(change(Operation.ADD,"A公司"),Change.keep()),"A公司"));
        assertThrows(ApiException.class,()->codec.validate(intent(change(Operation.REPLACE,"A公司","B公司"),Change.keep()),"A公司、B公司"));
        assertThrows(ApiException.class,()->codec.validate(intent(change(Operation.KEEP,"A公司"),Change.keep()),"A公司"));
        var clear=new Change(Operation.CLEAR,List.of(),"查询全部公司");
        assertDoesNotThrow(()->codec.validate(intent(clear,Change.keep()),"查询全部公司"));
    }
    @Test void refusedCompanyDoesNotBecomeEffectiveOrFallBackOnNextTurn() {
        var planner=new SemanticPlanner(new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties()));
        var state=new DialogueState();
        planner.merge(USER1,state,intent(change(Operation.REPLACE,"A公司"),Change.keep()));
        planner.validate(USER1,state);state.setEffective(state.getDesired());
        planner.merge(USER1,state,intent(change(Operation.REPLACE,"B公司"),Change.keep()));
        assertThrows(ApiException.class,()->planner.validate(USER1,state));
        assertEquals("A",state.getEffective().companyCode());assertEquals("B",state.getDesired().companyCode());
        planner.merge(USER1,state,intent(Change.keep(),change(Operation.REPLACE,"销售报表")));
        assertThrows(ApiException.class,()->planner.validate(USER1,state));
        assertEquals(List.of(SALES),state.getDesired().reportIds());
        planner.merge(USER1,state,intent(change(Operation.REPLACE,"A公司"),change(Operation.REPLACE,"销售报表")));
        assertDoesNotThrow(()->planner.validate(USER1,state));
        assertEquals("A",state.getDesired().companyCode());
    }
    @Test void reportDeltaReplayAndExplicitReset() {
        var planner=new SemanticPlanner(new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties()));
        var state=new DialogueState();
        planner.merge(USER1,state,intent(Change.keep(),change(Operation.REPLACE,"销售报表")));
        planner.merge(USER1,state,intent(Change.keep(),change(Operation.ADD,"应收报表")));
        assertEquals(List.of(SALES,RECEIVABLE),state.getDesired().reportIds());
        planner.merge(USER1,state,intent(Change.keep(),change(Operation.REMOVE,"销售报表")));
        assertEquals(List.of(RECEIVABLE),state.getDesired().reportIds());
        planner.merge(USER1,state,intent(Change.keep(),new Change(Operation.CLEAR,List.of(),"所有报表")));
        assertTrue(state.getDesired().allReports());
    }
    @Test void ambiguousAndUnavailableReportsCannotFallBackToOldReports() {
        var planner=new SemanticPlanner(new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties()));
        var state=new DialogueState();
        planner.merge(USER1,state,intent(Change.keep(),change(Operation.REPLACE,"销售报表")));
        assertThrows(ApiException.class,()->planner.merge(USER1,state,intent(Change.keep(),change(Operation.REPLACE,"客户对账"))));
        assertTrue(state.isUnresolvedReports());
        planner.merge(USER1,state,intent(Change.keep(),Change.keep()));
        assertThrows(ApiException.class,()->planner.validate(USER1,state));
        planner.merge(USER1,state,intent(Change.keep(),change(Operation.REPLACE,"应收报表")));
        assertFalse(state.isUnresolvedReports());
        assertThrows(ApiException.class,()->planner.validate(USER3,state));
    }
    @Test void duplicateDocumentNumberRequiresSelectionAndUniqueLabelKeepsExactIdentity() {
        var rows=List.of(candidate(SALES,"1","DUP","A","云服务"),candidate(RECEIVABLE,"1","DUP","A","其他服务"));
        assertThrows(ApiException.class,()->SelectionResolver.apply(rows,List.of(),change(Operation.ADD,"DUP")));
        var keys=SelectionResolver.apply(rows,List.of(),change(Operation.ADD,"云服务"));
        assertEquals(List.of(new RecordKey(SALES,"1")),keys);
        assertEquals(List.of(),SelectionResolver.apply(rows,keys,change(Operation.REMOVE,"云服务")));
        assertThrows(ApiException.class,()->SelectionResolver.apply(rows,List.of(),change(Operation.ADD,"服务")));
    }
    @Test void incompleteMentionsAndGenericEntitiesCannotExpandScope() {
        var planner=new SemanticPlanner(new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties()));
        var state=new DialogueState();
        var empty=intent(Change.keep(),Change.keep());
        assertThrows(ApiException.class,()->planner.requireCoverage(state,empty,planner.mentions(USER1,"只查销售报表")));
        assertTrue(state.isUnresolvedReports());
        assertThrows(ApiException.class,()->planner.merge(USER1,state,intent(change(Operation.REPLACE,"公司"),Change.keep())));
        assertTrue(state.isUnresolvedCompany());
        assertThrows(ApiException.class,()->planner.merge(USER1,state,intent(Change.keep(),change(Operation.REPLACE,"所有报表"))));
    }
}
