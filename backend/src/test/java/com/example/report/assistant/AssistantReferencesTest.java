package com.example.report.assistant;

import com.example.report.common.*;
import com.example.report.dispatch.*;
import com.example.report.semantic.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 跨任务对象引用不依赖措辞；身份、完整性、权限版本和目标来源变化必须使旧引用失效。 */
class AssistantReferencesTest {
    private DialogueState state() {
        var s=new DialogueState();s.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),"A",List.of(),null,false,1,20,null));
        s.setBusinessReferences(List.of(Map.of("reportId","r","recordId","1","companyCode","A","displayIndex","1"),
                Map.of("reportId","r","recordId","2","companyCode","A","displayIndex","2")));
        s.setBusinessTotalCount(2L);s.setBusinessPermissionVersion("v1");s.setBusinessQueryAfterPreview(true);return s;
    }
    private DispatchDirective task(DialogueState s,DispatchDirective.Source source,List<String> keys) {
        return new DispatchDirective(new SemanticIntent(1,Action.PREPARE_DISPATCH,List.of(),List.of(),Clarify.NONE),source,AssistantReferences.queryRef(s),keys,"处理刚才的对象");
    }
    @Test void queryCanDirectlyPrepareTheBoundSubsetWithoutAPreviousPreview() {
        var s=state();var task=task(s,DispatchDirective.Source.QUERY_ROWS,List.of("row-2"));
        assertDoesNotThrow(()->AssistantReferences.validate("处理刚才的对象",s,task));
        assertEquals(List.of(new RecordTarget(new RecordKey("r","2"),"A")),AssistantReferences.resolveQuery(s,task));
        assertNull(s.getPreviewId());assertTrue(s.isBusinessQueryAfterPreview());
        var restored=JsonUtil.MAPPER.convertValue(JsonUtil.MAPPER.valueToTree(s),DialogueState.class);
        assertEquals(AssistantReferences.queryRef(s),AssistantReferences.queryRef(restored));
    }
    @Test void completeAllAndDisplayedSubsetHaveDifferentBoundaries() {
        var s=state();assertEquals(2,AssistantReferences.resolveQuery(s,task(s,DispatchDirective.Source.QUERY_ALL,List.of())).size());
        s.setBusinessTotalCount(30L);
        assertThrows(ApiException.class,()->AssistantReferences.resolveQuery(s,task(s,DispatchDirective.Source.QUERY_ALL,List.of())));
        assertEquals(1,AssistantReferences.resolveQuery(s,task(s,DispatchDirective.Source.QUERY_ROWS,List.of("row-1"))).size());
        s.setBusinessQuery(s.getBusinessQuery().atPage(2));s.setBusinessTotalCount(2L);
        assertThrows(ApiException.class,()->AssistantReferences.resolveQuery(s,task(s,DispatchDirective.Source.QUERY_ALL,List.of())));
    }
    @Test void sourceMutationFailureAndWrongDomainCannotReuseReference() {
        var s=state();var task=task(s,DispatchDirective.Source.QUERY_ROWS,List.of("row-1"));
        s.setBusinessPermissionVersion("v2");assertThrows(ApiException.class,()->AssistantReferences.resolveQuery(s,task));
        s.setBusinessPermissionVersion("v1");s.setBusinessUnresolved(true);assertThrows(ApiException.class,()->AssistantReferences.resolveQuery(s,task));
        s.setBusinessUnresolved(false);s.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.LIST,List.of("r"),"A",List.of(),null,false,1,20,null));
        assertThrows(ApiException.class,()->AssistantReferences.resolveQuery(s,task));
    }
    @Test void nonexistentDuplicateOrOutOfCompanyTargetsCannotBecomePartialResults() {
        var s=state();assertThrows(ApiException.class,()->AssistantReferences.resolveQuery(s,task(s,DispatchDirective.Source.QUERY_ROWS,List.of("row-9"))));
        s.setBusinessReferences(List.of(s.getBusinessReferences().get(0),s.getBusinessReferences().get(0)));
        assertThrows(ApiException.class,()->AssistantReferences.resolveQuery(s,task(s,DispatchDirective.Source.QUERY_ALL,List.of())));
        s.setBusinessReferences(List.of(Map.of("reportId","r","recordId","1","companyCode","B")));
        assertThrows(ApiException.class,()->AssistantReferences.resolveQuery(s,task(s,DispatchDirective.Source.QUERY_ROWS,List.of("row-1"))));
    }
    @Test void queryTargetCannotAlsoChangeOldPreviewSelection() {
        var s=state();var mutation=new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),"处理刚才的对象");
        assertThrows(ApiException.class,()->new DispatchDirective(new SemanticIntent(1,Action.PREPARE_DISPATCH,List.of(mutation),List.of(),Clarify.NONE),
                DispatchDirective.Source.QUERY_ROWS,AssistantReferences.queryRef(s),List.of("row-1"),"处理刚才的对象"));
    }
    @Test void explicitScopeNeedsAnActualScopeAndOldPreviewNeedsItsOwnReference() {
        var s=state();var intent=new SemanticIntent(1,Action.PREVIEW,List.of(),List.of(),Clarify.NONE);
        var noScope=new DispatchDirective(intent,DispatchDirective.Source.EXPLICIT_SCOPE,null,List.of(),"查询候选");
        assertThrows(ApiException.class,()->AssistantReferences.validate("查询候选",s,noScope));
        s.setPreviewId("p");var preview=new DispatchDirective(intent,DispatchDirective.Source.PREVIEW,"preview-p",List.of(),"返回之前候选");
        assertDoesNotThrow(()->AssistantReferences.validate("返回之前候选",s,preview));
        s.setPreviewId("next");assertThrows(ApiException.class,()->AssistantReferences.validate("返回之前候选",s,preview));
    }
}
