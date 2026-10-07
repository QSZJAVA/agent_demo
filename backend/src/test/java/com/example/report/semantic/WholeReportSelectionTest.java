package com.example.report.semantic;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.RecordKey;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;

/** 整类勾选只影响当前授权快照内指定报表，逆操作和重复操作不得清除其他报表的排除记录。 */
class WholeReportSelectionTest {
    @Test void globalReportPhraseCannotReachPreviewAsConcreteRecordQualifier() {
        var state=new DialogueState();state.setExcludedRecords(List.of(new RecordKey(EXPENSE,"e")));var before=state.getDesired();
        var allReports=new ScopeChange(Target.REPORTS,Operation.CLEAR,List.of(),"所有报表");
        var invalidReset=new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),"从头来",List.of("所有报表"),SelectorKind.NONE,Quantifier.UNSPECIFIED);
        var invalid=new SemanticIntent(1,Action.PREVIEW,List.of(allReports,invalidReset),List.of(),Clarify.NONE);
        var planner=new SemanticPlanner(catalog);
        var failure=assertThrows(IntentCodec.InvalidOutput.class,()->planner.validateModelDraft(USER1,state,invalid));
        assertTrue(failure.reason().startsWith("RECORD_SCOPE_MUST_NAME_REPORT"));
        assertEquals(before,state.getDesired());assertEquals(List.of(new RecordKey(EXPENSE,"e")),state.getExcludedRecords());
        var reset=new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),"从头来",List.of(),SelectorKind.NONE,Quantifier.UNSPECIFIED);
        assertDoesNotThrow(()->planner.validateModelDraft(USER1,state,new SemanticIntent(1,Action.PREVIEW,List.of(allReports,reset),List.of(),Clarify.NONE)));
    }
    @Test void selectionOnlyRequestDoesNotAuthorizeAnAdditionalPendingPlan() {
        var change=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("交通"),"交通不选，其余照常",List.of(),SelectorKind.DESCRIPTION,Quantifier.ALL);
        var prepare=new SemanticIntent(1,Action.PREPARE_DISPATCH,List.of(change),List.of(),Clarify.NONE);
        assertTrue(SemanticConversationService.requiresExplicitPreparation(prepare,"交通不选，其余照常"));
        assertFalse(SemanticConversationService.requiresExplicitPreparation(prepare,"交通不选，其余生成待确认清单"));
        assertFalse(SemanticConversationService.requiresExplicitPreparation(prepare,"Exclude transport and draft a plan for the rest"));
        var selection=new SemanticIntent(1,Action.PREVIEW,List.of(change),List.of(),Clarify.NONE);
        assertFalse(SemanticConversationService.requiresExplicitPreparation(selection,"交通不选，其余照常"));
    }
    @Test void repairFeedbackDistinguishesMissingEntitiesFromForbiddenEntities() {
        var codec=new IntentCodec();
        var missing=new ScopeChange(Target.REPORTS,Operation.ADD,List.of(),"追加销售");
        var missingError=assertThrows(IntentCodec.InvalidOutput.class,()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(missing),List.of(),Clarify.NONE),missing.evidence()));
        assertTrue(missingError.reason().startsWith("SELECTOR_MENTIONS_REQUIRED"));
        var misplaced=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of("销售"),"销售加回来",List.of("销售"),SelectorKind.ALL,Quantifier.ALL);
        var misplacedError=assertThrows(IntentCodec.InvalidOutput.class,()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(misplaced),List.of(),Clarify.NONE),misplaced.evidence()));
        assertTrue(misplacedError.reason().startsWith("SELECTOR_MENTIONS_MUST_BE_EMPTY"));
        assertTrue(misplacedError.reason().contains("reportMentions"));
    }
    @Test void keepOnlySupportsGroundedTypedSelectorsWithoutChangingOtherReportSelections() {
        var rows=List.of(candidate(SALES,"s","S1","A","货物"),candidate(EXPENSE,"e1","E1","A","差旅费"),candidate(EXPENSE,"e2","E2","A","招待费"));
        for(var kind:List.of(SelectorKind.DOCUMENT,SelectorKind.DESCRIPTION)) {
            String term=kind==SelectorKind.DOCUMENT?"E1":"差旅费",message="费用里只保留"+term;
            var change=new ScopeChange(Target.RECORDS,Operation.KEEP_ONLY,List.of(term),message,List.of("费用"),kind,Quantifier.ONE);
            assertDoesNotThrow(()->new IntentCodec().validate(new SemanticIntent(1,Action.PREVIEW,List.of(change),List.of(),Clarify.NONE),message));
            var previous=List.of(new RecordKey(SALES,"s"));
            var result=SelectionResolver.apply(rows,previous,change,catalog,USER1);
            assertEquals(Set.of(new RecordKey(SALES,"s"),new RecordKey(EXPENSE,"e2")),Set.copyOf(result));
            assertEquals(result,SelectionResolver.apply(rows,result,change,catalog,USER1));
            var ordinary=new ScopeChange(Target.RECORDS,Operation.KEEP_ONLY,List.of(term),"保留"+term,List.of(),kind,Quantifier.ONE);
            assertThrows(IntentCodec.InvalidOutput.class,()->new IntentCodec().validate(new SemanticIntent(1,Action.PREVIEW,List.of(ordinary),List.of(),Clarify.NONE),ordinary.evidence()));
        }
    }
    @Test void modelRepairCannotReplaceRejectedPreparationWithUnscopedPreview() {
        var state=new DialogueState();state.setBusinessQueryAfterPreview(true);
        assertTrue(SemanticConversationService.requiresFreshDispatchScope(state,new SemanticIntent(1,Action.PREPARE_DISPATCH,List.of(),List.of(),Clarify.NONE),"刚才那些直接准备清单"));
        assertTrue(SemanticConversationService.requiresFreshDispatchScope(state,new SemanticIntent(1,Action.PREVIEW,List.of(),List.of(),Clarify.NONE),"刚才那些直接准备清单"));
        assertFalse(SemanticConversationService.requiresFreshDispatchScope(state,new SemanticIntent(1,Action.PREVIEW,List.of(),List.of(),Clarify.NONE),"查一下我有哪些可以派单"));
        var fresh=new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("费用"),"重新查费用")),List.of(),Clarify.NONE);
        assertFalse(SemanticConversationService.requiresFreshDispatchScope(state,fresh,"重新查费用"));
        state.setBusinessQueryAfterPreview(false);assertFalse(SemanticConversationService.requiresFreshDispatchScope(state,new SemanticIntent(1,Action.PREVIEW,List.of(),List.of(),Clarify.NONE),"查询"));
    }
    @Test void actionClarificationAfterBusinessQueryExplainsDispatchBoundary() {
        var state=new DialogueState();state.setBusinessQueryAfterPreview(true);
        var planner=new SemanticPlanner(catalog);
        var failure=assertThrows(com.example.report.common.ApiException.class,()->planner.requireAction(state,SemanticIntent.clarify(Clarify.ACTION)));
        assertTrue(failure.getMessage().contains("只读"));assertTrue(failure.getMessage().contains("可派候选"));assertTrue(state.isUnresolvedRequest());
    }
    @Test void companyTypeLabelsNormalizeWithoutFuzzyNameOrIdentifierRewriting() {
        for(String label:List.of("A公司","公司 A","company a","A company"))assertEquals("A",SemanticPlanner.companyCode(label));
        assertEquals("COMPANY-A",SemanticPlanner.companyCode("COMPANY-A"));
        assertEquals("ACMECOMPANY",SemanticPlanner.companyCode("AcmeCompany"));
    }
    final ReportCatalogService catalog=new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties());
    @Test void scopedWholeReportExclusionAndRestorePreserveOtherSelections() {
        var rows=List.of(candidate(SALES,"s1","S1","A","销售一"),candidate(SALES,"s2","S2","A","销售二"),candidate(EXPENSE,"e1","E1","A","费用"));
        var previous=List.of(new RecordKey(EXPENSE,"e1"));
        var exclude=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"销售全部不选",List.of("销售"),SelectorKind.ALL,Quantifier.ALL);
        var selected=SelectionResolver.apply(rows,previous,exclude,catalog,USER1);
        assertEquals(Set.of(new RecordKey(SALES,"s1"),new RecordKey(SALES,"s2"),new RecordKey(EXPENSE,"e1")),Set.copyOf(selected));
        assertEquals(selected,SelectionResolver.apply(rows,selected,exclude,catalog,USER1));
        var restore=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of(),"销售全部恢复",List.of("销售"),SelectorKind.ALL,Quantifier.ALL);
        assertEquals(previous,SelectionResolver.apply(rows,selected,restore,catalog,USER1));
    }
    @Test void wholeScopeSelectionNeedsExplicitAllCardinalityAndNoFabricatedSelector() {
        var codec=new IntentCodec();var good=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"全部先不选",List.of(),SelectorKind.ALL,Quantifier.ALL);
        assertDoesNotThrow(()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(good),List.of(),Clarify.NONE),good.evidence()));
        var singular=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),"这一条不选",List.of(),SelectorKind.ALL,Quantifier.ONE);
        assertThrows(IntentCodec.InvalidOutput.class,()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(singular),List.of(),Clarify.NONE),singular.evidence()));
        var fabricated=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("未知对象"),"未知对象全部不选",List.of(),SelectorKind.ALL,Quantifier.ALL);
        assertThrows(IntentCodec.InvalidOutput.class,()->codec.validate(new SemanticIntent(1,Action.PREVIEW,List.of(fabricated),List.of(),Clarify.NONE),fabricated.evidence()));
    }
}
