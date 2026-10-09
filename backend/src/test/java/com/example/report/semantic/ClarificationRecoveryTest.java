package com.example.report.semantic;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.ApiException;
import com.example.report.config.AgentProperties;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.TestCatalog.USER1;
import static org.junit.jupiter.api.Assertions.*;

/** 未应用的候选范围不能污染已确定范围；实体歧义由执行层阻断，动作与数量含义另在统一模型复核测试中验证。 */
class ClarificationRecoveryTest {
    private final SemanticPlanner planner=new SemanticPlanner(new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties()));
    @org.junit.jupiter.api.Test void preservingOtherReportsRequiresAnExplicitBoundaryAndDoesNotPoisonKnownScope() {
        var state=new DialogueState();var constraint=new ReportConstraint("其他报表",ReportRole.UNCHANGED_OTHERS,"其他报表不动");
        var scoped=new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),"恢复销售",List.of("销售"),SelectorKind.NONE,Quantifier.UNSPECIFIED);
        assertDoesNotThrow(()->planner.merge(USER1,state,new SemanticIntent(1,Action.PREVIEW,List.of(scoped),List.of(),List.of(constraint),List.of(),Clarify.NONE)));
        var unscoped=new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),"恢复全部",List.of(),SelectorKind.NONE,Quantifier.UNSPECIFIED);
        assertThrows(IntentCodec.InvalidOutput.class,()->planner.merge(USER1,state,new SemanticIntent(1,Action.PREVIEW,List.of(unscoped),List.of(),List.of(constraint),List.of(),Clarify.NONE)));
        assertFalse(state.isUnresolvedReports());assertTrue(state.isUnresolvedRequest());
    }
    @org.junit.jupiter.api.Test void unchangedReportProtectsMembershipAndSelectionWhileOtherReportRestores() {
        var state=new DialogueState();var previous=state.getDesired();
        var protectedReport=new ReportConstraint("费用",ReportRole.UNCHANGED,"费用保持不变");
        var restore=new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),"恢复销售",List.of("销售"),SelectorKind.NONE,Quantifier.UNSPECIFIED);
        var intent=new SemanticIntent(1,Action.PREVIEW,List.of(restore),List.of(),List.of(protectedReport),List.of(),Clarify.NONE);
        assertDoesNotThrow(()->{new IntentCodec().validate(intent,"恢复销售，费用保持不变");planner.merge(USER1,state,intent);});
        assertEquals(previous,state.getDesired());
        var rows=List.of(com.example.report.support.DispatchHarness.candidate(TestCatalog.SALES,"s","SO","A","销售"),com.example.report.support.DispatchHarness.candidate(TestCatalog.EXPENSE,"e","EX","A","费用"));
        var selected=List.of(new com.example.report.dispatch.RecordKey(TestCatalog.SALES,"s"),new com.example.report.dispatch.RecordKey(TestCatalog.EXPENSE,"e"));
        assertEquals(List.of(selected.get(1)),SelectionResolver.apply(rows,selected,restore,new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties()),USER1));
        var unscoped=new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),"全部恢复",List.of(),SelectorKind.NONE,Quantifier.UNSPECIFIED);
        assertThrows(IntentCodec.InvalidOutput.class,()->planner.merge(USER1,state,new SemanticIntent(1,Action.PREVIEW,List.of(unscoped),List.of(),List.of(protectedReport),List.of(),Clarify.NONE)));
        var remove=new ScopeChange(Target.REPORTS,Operation.REMOVE,List.of("费用"),"移除费用");
        assertThrows(IntentCodec.InvalidOutput.class,()->planner.merge(USER1,new DialogueState(),new SemanticIntent(1,Action.PREVIEW,List.of(remove),List.of(),List.of(protectedReport),List.of(),Clarify.NONE)));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"销售报表","应收报表","费用报表"})
    void reportQualifierCanReferToAnotherClauseInTheSameTurn(String report) {
        String message="查询"+report+"，单据DOC-17不要";
        var intent=new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("DOC-17"),"单据DOC-17不要",List.of(report))),List.of(),List.of(),List.of(),Clarify.NONE);
        assertDoesNotThrow(()->new IntentCodec().validate(intent,message));
        assertThrows(ApiException.class,()->new IntentCodec().validate(intent,"单据DOC-17不要"));
    }
    @org.junit.jupiter.api.Test void repairReportsEvidenceRewriteWithoutRejectingSharedClause() {
        String message="销售报表只留金额十到二十的记录";
        var draft=new SemanticIntent(1,Action.PREVIEW,List.of(
                new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("销售报表"),message),
                new ScopeChange(Target.RECORDS,Operation.KEEP_ONLY,List.of(),message,List.of("销售报表"),SelectorKind.FIELDS,Quantifier.ALL,
                        List.of(new ConditionGroup(List.of(new FieldCondition("amount",Comparison.GTE,List.of("10"),"金额大于等于十")))))),List.of(),Clarify.NONE);
        var error=assertThrows(IntentCodec.InvalidOutput.class,()->new IntentCodec().decode(com.example.report.common.JsonUtil.toJson(draft),message));
        assertTrue(error.reason().contains("条件evidence须逐字"));
        assertFalse(error.reason().contains("REPORTS范围修改缺少独立原文依据"));
    }
    @ParameterizedTest @EnumSource(value=Target.class,names={"COMPANY","REPORTS"})
    void unsupportedRequestWithDraftScopeAllowsExplicitRecordRecovery(Target target) {
        var state=new DialogueState();state.setEffective(state.getDesired());
        var scope=state.getDesired();
        var intent=new SemanticIntent(1,Action.CLARIFY,List.of(new ScopeChange(target,Operation.REPLACE,
                List.of(target==Target.COMPANY?"A":"销售报表"),"候选范围")),List.of(),Clarify.ACTION);
        assertThrows(ApiException.class,()->planner.merge(USER1,state,intent));
        assertEquals(scope,state.getDesired());assertFalse(state.isUnresolvedCompany());assertFalse(state.isUnresolvedReports());
        assertTrue(state.isUnresolvedRequest());
        // 本层只接收已通过统一语义复核和来源绑定的动作；不再单凭历史失败否决明确的新请求。
        assertDoesNotThrow(()->planner.requireAction(state,new SemanticIntent(1,Action.PREPARE_DISPATCH,List.of(),List.of(),Clarify.NONE)));
        var correction=new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("SO1"),"SO1不要")),List.of(),Clarify.NONE);
        assertDoesNotThrow(()->{planner.merge(USER1,state,correction);planner.validate(USER1,state);});
    }
    @ParameterizedTest @EnumSource(value=Clarify.class,names={"COMPANY","REPORTS"})
    void actualScopeAmbiguityStillBlocksRecordOnlyContinuation(Clarify clarify) {
        var state=new DialogueState();
        assertThrows(ApiException.class,()->planner.requireAction(state,new SemanticIntent(1,Action.CLARIFY,List.of(),List.of(),clarify)));
        assertThrows(ApiException.class,()->planner.validate(USER1,state));
    }
}
