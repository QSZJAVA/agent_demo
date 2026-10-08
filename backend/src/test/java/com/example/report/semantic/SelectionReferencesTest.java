package com.example.report.semantic;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.RecordKey;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;

/** 跨轮引用必须绑定当前预览和授权复合记录；未知、过期、跨范围以及单笔歧义均不能修改选择。 */
class SelectionReferencesTest {
    @Test void resolvedReferenceNeverChangesIdentityAccordingToWording() {
        var state=state();String ref=state.getLastSelectionReferences().get(0).get("referenceKey");
        var original=List.of(new RecordKey(EXPENSE,"e"));
        var change=new ScopeChange(Target.RECORDS,Operation.KEEP_ONLY,List.of(ref),"保留刚选定的对象",List.of(),SelectorKind.REFERENCE,Quantifier.ONE);
        // 原意与目标的符合性由统一语义复核负责；记录执行层只处理已经绑定的键，不从措辞重新猜另一对象。
        assertEquals(original,SelectionResolver.apply(rows,original,change,catalog,USER1,state));
        assertEquals(new RecordKey(SALES,"s"),state.getLastSelectionReferenceKeys().get(ref));
        assertEquals(List.of(new RecordKey(EXPENSE,"e")),original);
    }
    @Test void keepOnlyReferenceStillRequiresCurrentPreviewBinding() {
        var state=state();String ref=state.getLastSelectionReferences().get(0).get("referenceKey");
        var only=new ScopeChange(Target.RECORDS,Operation.KEEP_ONLY,List.of(ref),"只留刚才那一笔",List.of(),SelectorKind.REFERENCE,Quantifier.ONE);
        assertDoesNotThrow(()->new IntentCodec().validate(new SemanticIntent(1,Action.PREVIEW,List.of(only),List.of(),Clarify.NONE),only.evidence()));
        assertEquals(List.of(new RecordKey(EXPENSE,"e")),SelectionResolver.apply(rows,List.of(),only,catalog,USER1,state));
        state.setPreviewId("other");assertThrows(com.example.report.common.ApiException.class,()->SelectionResolver.apply(rows,List.of(),only,catalog,USER1,state));
    }
    final ReportCatalogService catalog=new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties());
    final List<com.example.report.rule.Candidate> rows=List.of(candidate(SALES,"s","S1","A","设备"),candidate(EXPENSE,"e","E1","A","交通"));
    DialogueState state() {var state=new DialogueState();state.setPreviewId("p1");SelectionReferences.capture(state,rows,Set.of(new RecordKey(SALES,"s"),new RecordKey(EXPENSE,"e")));return state;}
    ScopeChange change(List<String> refs,List<String> reports,Quantifier quantity) {return new ScopeChange(Target.RECORDS,Operation.RESTORE,refs,"恢复刚才的记录",reports,SelectorKind.REFERENCE,quantity);}
    @Test void restoresOnlyBoundRecordAndRejectsUnknownOrDifferentPreview() {
        var state=state();var original=List.of(new RecordKey(SALES,"s"),new RecordKey(EXPENSE,"e"));
        String ref=state.getLastSelectionReferences().get(0).get("referenceKey");var change=change(List.of(ref),List.of(),Quantifier.ONE);
        assertDoesNotThrow(()->new IntentCodec().validate(new SemanticIntent(1,Action.PREVIEW,List.of(change),List.of(),Clarify.NONE),change.evidence()));
        assertEquals(List.of(new RecordKey(EXPENSE,"e")),SelectionResolver.apply(rows,original,change,catalog,USER1,state));
        assertThrows(com.example.report.common.ApiException.class,()->SelectionResolver.apply(rows,original,change(List.of("ref_"+"f".repeat(32)),List.of(),Quantifier.ONE),catalog,USER1,state));
        state.setPreviewId("p2");assertThrows(com.example.report.common.ApiException.class,()->SelectionResolver.apply(rows,original,change,catalog,USER1,state));
        assertEquals(2,original.size());
    }
    @Test void cannotUseReferenceAcrossReportScopeOrHideSeveralRecordsAsOne() {
        var state=state();var refs=state.getLastSelectionReferences().stream().map(r->r.get("referenceKey")).toList();
        assertThrows(com.example.report.common.ApiException.class,()->SelectionResolver.apply(rows,List.of(),change(List.of(refs.get(0)),List.of("费用"),Quantifier.ONE),catalog,USER1,state));
        assertThrows(com.example.report.common.ApiException.class,()->SelectionResolver.apply(rows,List.of(),change(refs,List.of(),Quantifier.ONE),catalog,USER1,state));
        assertThrows(com.example.report.common.ApiException.class,()->SelectionResolver.apply(rows.subList(0,1),List.of(),change(refs,List.of(),Quantifier.ALL),catalog,USER1,state));
    }
    @Test void rebindingInvalidatesPreviousKeysAndTruncationExposesOnlyBoundFacts() {
        var state=state();String old=state.getLastSelectionReferences().get(0).get("referenceKey");
        SelectionReferences.capture(state,rows,Set.of(new RecordKey(SALES,"s")));
        assertThrows(com.example.report.common.ApiException.class,()->SelectionReferences.resolve(state,rows,change(List.of(old),List.of(),Quantifier.ONE)));
        var many=new ArrayList<com.example.report.rule.Candidate>();var keys=new HashSet<RecordKey>();
        for(int i=0;i<55;i++){many.add(candidate(SALES,"s"+i,"S"+i,"A","记录"));keys.add(new RecordKey(SALES,"s"+i));}
        SelectionReferences.capture(state,many,keys);assertEquals(50,state.getLastSelectionReferenceKeys().size());assertFalse(state.isLastSelectionReferencesComplete());
        SelectionReferences.clear(state);assertTrue(state.getLastSelectionReferenceKeys().isEmpty());assertNull(state.getLastSelectionPreviewId());
    }
    @Test void preparationContextDescribesActualSelectionWithoutChangingIt() {
        var state=state();state.setExcludedRecords(List.of(new RecordKey(SALES,"s")));
        var summary=SelectionReferences.describeSelection(state,rows);
        assertEquals(2,summary.get("totalCount"));assertEquals(1,summary.get("selectedCount"));assertEquals(true,summary.get("complete"));
        assertTrue(summary.get("selectedRows").toString().contains("E1"));assertFalse(summary.get("selectedRows").toString().contains("S1"));
        assertEquals(List.of(new RecordKey(SALES,"s")),state.getExcludedRecords());
        var many=new ArrayList<com.example.report.rule.Candidate>();for(int i=0;i<55;i++)many.add(candidate(SALES,"x"+i,"X"+i,"A","记录"));
        var bounded=SelectionReferences.describeSelection(state,many);assertEquals(55,bounded.get("selectedCount"));assertEquals(false,bounded.get("complete"));
    }
}
