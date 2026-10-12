package com.example.report.semantic;

import com.example.report.analysis.*;
import com.example.report.assistant.*;
import com.example.report.common.*;
import com.example.report.conversation.*;
import com.example.report.permission.CurrentUser;
import com.example.report.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 聊天分析接续的服务边界回归；测试替身只验证程序约束，不作为真实模型或HTTP MCP验收。 */
class ChatAnalysisBoundaryTest {
    private static final String REPORT="rpt-sales-order", SNAPSHOT="a".repeat(32), REQUEST="b".repeat(32), RUN="c".repeat(32);
    private static final CurrentUser USER=new CurrentUser("tenant-chat","chat-user","测试用户",Set.of("A"),Set.of(),true);
    private final AnalysisService analyses=mock(AnalysisService.class);
    private final QueryWorkspaceService workspace=mock(QueryWorkspaceService.class);
    private final ChatTurnReceiptStore receipts=mock(ChatTurnReceiptStore.class);
    private final ConversationService conversations=mock(ConversationService.class);
    private final ChatAnalysisService service=new ChatAnalysisService(analyses,workspace,receipts,conversations);
    private final DialogueState state=new DialogueState();
    private final BusinessQuery query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(REPORT),"A",List.of(),null,false,1,20,null);

    ChatAnalysisBoundaryTest() {
        state.setAssistantFocus("BUSINESS_QUERY");state.setBusinessQuery(query);state.setBusinessQueryRef("query-turn");
        state.setBusinessSnapshotId(SNAPSHOT);state.setBusinessSnapshotComplete(true);state.setBusinessPermissionVersion(USER.permissionVersion());
        var snapshot=new BusinessResult.Snapshot(SNAPSHOT,Instant.now().plusSeconds(600).toString(),"COMPLETE","本地测试读取边界","e".repeat(64),0);
        var result=new BusinessResult(query,Instant.now().toString(),"程序测试事实",List.of(),List.of(),0,new BusinessResult.Summary(0,Map.of(),Map.of(),Map.of(),Map.of(),0),snapshot);
        when(workspace.requireComplete(USER,SNAPSHOT)).thenReturn(new QuerySnapshotStore.Run(result,"d".repeat(64),Set.of("A")));
        when(analyses.prepare(eq(USER),any())).thenAnswer(call->prepared(call.getArgument(1)));
    }
    private AnalysisDirective snapshotDirective() {return new AnalysisDirective("A",REPORT,AnalysisContract.Mode.SNAPSHOT_ONLY,ChatAnalysisService.sourceRef(state));}
    private static AnalysisService.Prepared prepared(AnalysisContract.Request request) {return new AnalysisService.Prepared(request,"d".repeat(64),"e".repeat(64));}
    private Map<String,Object> ready(AnalysisContract.Request request) {
        var row=new HashMap<String,Object>();row.put("request_id",REQUEST);row.put("conversation_id","conversation-test");row.put("client_key","chat-key-test");row.put("status","READY");
        row.put("trusted_request_json",JsonUtil.toJson(request));row.put("scope_hash","d".repeat(64));row.put("config_hash","e".repeat(64));
        row.put("expires_at",LocalDateTime.ofInstant(Instant.now().plusSeconds(300),ZoneOffset.UTC));return row;
    }
    @Test void sourceContextContainsOpaqueReferenceAndExactQueryButNoSnapshotId() {
        var context=service.context(USER,state);assertEquals(true,context.get("available"));
        var source=(Map<?,?>)context.get("source");assertEquals(ChatAnalysisService.sourceRef(state),source.get("sourceRef"));assertEquals(query,source.get("query"));
        assertFalse(JsonUtil.toJson(context).contains(SNAPSHOT));assertEquals("COMPLETE",source.get("completeness"));
    }
    @Test void sameConditionsNewSnapshotGetsDifferentReference() {
        String original=ChatAnalysisService.sourceRef(state);state.setBusinessSnapshotId("f".repeat(32));
        assertNotEquals(original,ChatAnalysisService.sourceRef(state));
    }
    @ParameterizedTest @ValueSource(strings={"ANALYSIS","KNOWLEDGE","DISPATCH"})
    void otherFocusCannotImplicitlyResumeOldQuery(String focus) {
        var directive=snapshotDirective();state.setAssistantFocus(focus);
        assertEquals(false,service.context(USER,state).get("available"));assertThrows(ApiException.class,()->service.preflight(USER,state,directive,"只分析刚才这些"));
        verifyNoInteractions(analyses);verify(workspace,never()).requireComplete(any(),any());
    }
    @Test void failedIncompleteOrAmbiguousSourceNeverBecomesCurrentQuery() {
        var directive=snapshotDirective();state.setBusinessUnresolved(true);
        assertThrows(ApiException.class,()->service.preflight(USER,state,directive,"只分析原查询"));
        state.setBusinessUnresolved(false);state.setBusinessSnapshotComplete(false);
        assertThrows(ApiException.class,()->service.preflight(USER,state,directive,"只分析原查询"));
        state.setBusinessSnapshotComplete(true);state.setBusinessQuery(new BusinessQuery(query.domain(),query.view(),query.reportIds(),null,query.conditions(),null,false,1,20,null));
        assertThrows(ApiException.class,()->service.preflight(USER,state,directive,"只分析原查询"));verifyNoInteractions(analyses);
    }
    @Test void snapshotPreflightBindsFullOriginalQuestionAndDoesNotSubmit() {
        String message="  只分析刚才这些销售数据，按产品分组后取金额最大的两组；不要重新查当前数据。  ";
        service.preflight(USER,state,snapshotDirective(),message);
        verify(analyses).prepare(USER,new AnalysisContract.Request("A",REPORT,message,AnalysisContract.Mode.SNAPSHOT_ONLY,SNAPSHOT));
        verify(analyses,never()).submitPrepared(any(),any(),any());verifyNoInteractions(receipts,conversations);
    }
    @Test void staleReferenceAndScopeMismatchFailBeforeAnalysisPreparation() {
        var old=snapshotDirective();state.setBusinessQueryRef("new-query-turn");
        assertThrows(ApiException.class,()->service.preflight(USER,state,old,"继续原数据"));
        var other=new AnalysisDirective("A","rpt-expense",AnalysisContract.Mode.SNAPSHOT_ONLY,ChatAnalysisService.sourceRef(state));
        assertThrows(ApiException.class,()->service.preflight(USER,state,other,"继续原数据"));verifyNoInteractions(analyses);
    }
    @Test void expiredSourceRejectsContinuationButExplicitKnowledgeNeedsNoSource() {
        var directive=snapshotDirective();when(workspace.requireComplete(USER,SNAPSHOT)).thenThrow(new ApiException(410,"快照已到期"));
        assertEquals(false,service.context(USER,state).get("available"));assertThrows(ApiException.class,()->service.preflight(USER,state,directive,"只分析原查询"));
        service.preflight(USER,state,new AnalysisDirective("A",REPORT,AnalysisContract.Mode.KNOWLEDGE_ONLY,null),"解释销售派单条件");
        verify(analyses).prepare(USER,new AnalysisContract.Request("A",REPORT,"解释销售派单条件",AnalysisContract.Mode.KNOWLEDGE_ONLY,null));
    }
    @Test void operationalSourceFailureIsNotMisrepresentedAsAbsentSource() {
        when(workspace.requireComplete(USER,SNAPSHOT)).thenThrow(new ApiException(503,"工作区暂不可用"));
        assertEquals(503,assertThrows(ApiException.class,()->service.context(USER,state)).getCode());verifyNoInteractions(analyses);
    }
    @Test void readyRestorationUsesFrozenInputAndOriginalSubtaskKey() {
        var request=new AnalysisContract.Request("A",REPORT,"解释制度",AnalysisContract.Mode.KNOWLEDGE_ONLY,null);var row=ready(request);
        when(receipts.requireRequest(USER,REQUEST)).thenReturn(row);when(analyses.submitPrepared(USER,prepared(request),"analysis-"+REQUEST)).thenReturn(Map.of("id",RUN));
        var session=mock(DialogueStore.Session.class);when(session.fenced(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        var reference=service.submitReady(USER,REQUEST,session,()->{});
        assertEquals(Set.of("requestId","runId","mode","companyCode","reportId"),reference.keySet());assertEquals(RUN,reference.get("runId"));
        var ordered=inOrder(analyses,receipts,conversations);ordered.verify(receipts).requireRequest(USER,REQUEST);
        ordered.verify(analyses).submitPrepared(USER,prepared(request),"analysis-"+REQUEST);ordered.verify(receipts).submitted(USER,REQUEST,RUN);
        ordered.verify(conversations).logAnalysisTask("conversation-test",USER.userId(),REQUEST,reference);verifyNoInteractions(workspace);
    }
    @Test void changedFrozenConfigurationCannotAssociateOrEmitCard() {
        var request=new AnalysisContract.Request("A",REPORT,"解释制度",AnalysisContract.Mode.KNOWLEDGE_ONLY,null);var row=ready(request);
        state.setAssistantRoute("KNOWLEDGE");state.setAssistantFocus("KNOWLEDGE");state.setPhase(DialogueState.Phase.READY);
        String acceptedState=JsonUtil.toJson(state);
        when(receipts.requireRequest(USER,REQUEST)).thenReturn(row);when(analyses.submitPrepared(any(),any(),any())).thenThrow(new AnalysisService.PreparedInvalidated(409,"依据已变化"));
        when(receipts.historyReady(USER,"conversation-test")).thenReturn(true);
        doAnswer(call->{row.put("status","FAILED");row.put("message","依据已变化");return null;}).when(receipts).invalidateReady(USER,REQUEST,"依据已变化");
        var session=mock(DialogueStore.Session.class);when(session.fenced(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        assertEquals(409,assertThrows(ApiException.class,()->service.submitReady(USER,REQUEST,session,()->{})).getCode());
        verify(receipts,never()).submitted(any(),any(),any());verify(receipts).invalidateReady(USER,REQUEST,"依据已变化");verifyNoInteractions(conversations);
        assertEquals(acceptedState,JsonUtil.toJson(state));verify(session,never()).save();
        // 最新轮次的失败同样由原回执表达，前端可展示原因并结束待核对，不依赖共享会话phase变为REJECTED。
        var view=service.receipt(USER,row);assertEquals("FAILED",view.get("status"));assertEquals(false,view.get("canReconcile"));
        assertEquals(true,view.get("historyReady"));assertEquals("依据已变化",view.get("message"));assertNull(view.get("analysisTask"));
    }
    @ParameterizedTest @ValueSource(ints={409,410,422})
    void invalidatedEarlierReadyCannotPoisonLaterSuccessfulQuery(int code) {
        var original=new AnalysisContract.Request("A",REPORT,"只分析原查询",AnalysisContract.Mode.SNAPSHOT_ONLY,SNAPSHOT);
        var row=ready(original);String reason="原分析依据已失效";
        state.setAssistantRoute("ANALYSIS");state.setAssistantFocus("ANALYSIS");state.setPhase(DialogueState.Phase.READY);
        when(analyses.revalidatePrepared(USER,prepared(original))).thenThrow(new AnalysisService.PreparedInvalidated(code,reason));
        when(analyses.submitPrepared(USER,prepared(original),"analysis-"+REQUEST)).thenThrow(new AnalysisService.PreparedInvalidated(code,reason));
        when(receipts.requireRequest(USER,REQUEST)).thenReturn(row);when(receipts.historyReady(USER,"conversation-test")).thenReturn(true);
        doAnswer(call->{row.put("status","FAILED");row.put("message",reason);return null;}).when(receipts).invalidateReady(USER,REQUEST,reason);

        // A的GET只投影失效终态，原READY仍在；客户端据此结束待核对状态后，可以继续提交新问题。
        var failedView=service.receipt(USER,row);
        assertEquals("FAILED",failedView.get("status"));assertEquals(false,failedView.get("canReconcile"));assertEquals(true,failedView.get("historyReady"));
        assertEquals("READY",row.get("status"));verify(receipts,never()).invalidateReady(any(),any(),any());

        // 模拟B成功查询保存的新快照和当前状态；同条件的新查询也必须有自己的轮次和来源引用。
        String newerSnapshot="f".repeat(32),newerQueryRef="9".repeat(32),observed=Instant.now().toString(),expires=Instant.now().plusSeconds(600).toString();
        state.setAssistantRoute("BUSINESS_QUERY");state.setAssistantFocus("BUSINESS_QUERY");state.setPhase(DialogueState.Phase.READY);
        state.setUnresolvedRequest(false);state.setBusinessUnresolved(false);state.setLastReason(null);
        state.setBusinessQuery(query);state.setBusinessQueryRef(newerQueryRef);state.setBusinessSnapshotId(newerSnapshot);
        state.setBusinessSnapshotObservedAt(observed);state.setBusinessSnapshotExpiresAt(expires);state.setBusinessSnapshotComplete(true);
        state.setRecentUserMessages(List.of(original.question(),"重新查询A公司销售报表"));
        var snapshot=new BusinessResult.Snapshot(newerSnapshot,expires,"COMPLETE","本地测试读取边界","f".repeat(64),0);
        var result=new BusinessResult(query,observed,"程序测试事实",List.of(),List.of(),0,new BusinessResult.Summary(0,Map.of(),Map.of(),Map.of(),Map.of(),0),snapshot);
        when(workspace.requireComplete(USER,newerSnapshot)).thenReturn(new QuerySnapshotStore.Run(result,"d".repeat(64),Set.of("A")));
        String acceptedState=JsonUtil.toJson(state);var acceptedContext=service.context(USER,state);var newerDirective=snapshotDirective();
        assertEquals(true,acceptedContext.get("available"));

        // A的迟到核对虽取得同会话的新租约，也只能结束A回执，不能改写当前已经属于B的状态。
        var session=mock(DialogueStore.Session.class);when(session.state()).thenReturn(state);when(session.fenced(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        assertEquals(code,assertThrows(ApiException.class,()->service.submitReady(USER,REQUEST,session,()->{})).getCode());
        verify(receipts).invalidateReady(USER,REQUEST,reason);verify(receipts,never()).submitted(any(),any(),any());verifyNoInteractions(conversations);
        assertAll(
                ()->assertEquals(acceptedState,JsonUtil.toJson(state),"旧请求核对不能覆盖B的任何权威状态"),
                ()->assertEquals(DialogueState.Phase.READY,state.getPhase()),
                ()->assertEquals("BUSINESS_QUERY",state.getAssistantFocus()),
                ()->assertFalse(state.isUnresolvedRequest()),
                ()->assertFalse(state.isBusinessUnresolved()),
                ()->assertEquals(newerQueryRef,state.getBusinessQueryRef()),
                ()->assertEquals(newerSnapshot,state.getBusinessSnapshotId()),
                ()->assertEquals(acceptedContext,service.context(USER,state)),
                ()->assertDoesNotThrow(()->service.preflight(USER,state,newerDirective,"只分析本轮查询，不重查业务数据")));
        verify(session,never()).save();
        verify(analyses).prepare(USER,new AnalysisContract.Request("A",REPORT,"只分析本轮查询，不重查业务数据",AnalysisContract.Mode.SNAPSHOT_ONLY,newerSnapshot));
        var settled=service.receipt(USER,row);assertEquals("FAILED",settled.get("status"));assertEquals(false,settled.get("canReconcile"));assertEquals(reason,settled.get("message"));
    }
    @Test void transientReadyFailureKeepsTrustedRequestForExplicitReconciliation() {
        var request=new AnalysisContract.Request("A",REPORT,"解释制度",AnalysisContract.Mode.KNOWLEDGE_ONLY,null);
        when(receipts.requireRequest(USER,REQUEST)).thenReturn(ready(request));when(analyses.submitPrepared(any(),any(),any())).thenThrow(new ApiException(503,"服务暂不可用"));
        var session=mock(DialogueStore.Session.class);when(session.fenced(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        assertEquals(503,assertThrows(ApiException.class,()->service.submitReady(USER,REQUEST,session,()->{})).getCode());
        verify(receipts,never()).invalidateReady(any(),any(),any());verify(receipts,never()).submitted(any(),any(),any());verifyNoInteractions(conversations);
    }
    @Test void readOnlyReceiptMarksInvalidReadyAsSettledWithoutCreatingOrChangingTask() {
        var request=new AnalysisContract.Request("A",REPORT,"解释制度",AnalysisContract.Mode.KNOWLEDGE_ONLY,null);var row=ready(request);
        when(analyses.revalidatePrepared(USER,prepared(request))).thenThrow(new AnalysisService.PreparedInvalidated(410,"原快照已过期"));
        var view=service.receipt(USER,row);assertEquals("FAILED",view.get("status"));assertEquals(false,view.get("canReconcile"));assertEquals("原快照已过期",view.get("message"));
        assertEquals("READY",row.get("status"));verify(analyses,never()).submitPrepared(any(),any(),any());
        verify(receipts).historyReady(USER,"conversation-test");verify(receipts,never()).invalidateReady(any(),any(),any());verify(receipts,never()).submitted(any(),any(),any());verifyNoInteractions(conversations);
    }
    @Test void historyRequiresExactConversationTurnTaskBindingAndDiscardsCachedAnswer() {
        var request=new AnalysisContract.Request("A",REPORT,"解释制度",AnalysisContract.Mode.KNOWLEDGE_ONLY,null);var row=ready(request);row.put("status","SUBMITTED");row.put("analysis_run_id",RUN);
        when(receipts.requireRequest(USER,REQUEST)).thenReturn(row);when(analyses.getChatTask(USER,RUN,REQUEST,prepared(request))).thenReturn(Map.of("request",request));
        var reference=Map.<String,Object>of("requestId",REQUEST,"runId",RUN,"mode","KNOWLEDGE_ONLY","companyCode","A","reportId",REPORT);
        var message=new MessageView(1L,"card",null,"analysis_task",reference,null,null,LocalDateTime.now(),null,null,null);
        assertEquals(reference,service.history(USER,"conversation-test",message).payload());assertEquals(REQUEST,service.history(USER,"conversation-test",message).requestId());
        assertEquals("UNAVAILABLE",service.history(USER,"wrong-conversation",message).status());
        var poisoned=new HashMap<>(reference);poisoned.put("answer","缓存敏感答案");
        var rejected=service.history(USER,"conversation-test",new MessageView(1L,"card","缓存文本","analysis_task",poisoned,null,null,LocalDateTime.now(),null,null,null));
        assertTrue(rejected.payload().isEmpty());assertNull(rejected.content());assertEquals("UNAVAILABLE",rejected.status());
    }
    @Test void inaccessibleTaskReceiptDoesNotExposePriorScopeOrCachedAnswer() {
        var request=new AnalysisContract.Request("A",REPORT,"解释制度",AnalysisContract.Mode.KNOWLEDGE_ONLY,null);var row=ready(request);row.put("status","SUBMITTED");row.put("analysis_run_id",RUN);
        when(analyses.getChatTask(USER,RUN,REQUEST,prepared(request))).thenThrow(ApiException.notFound("任务已不可用"));
        var receipt=service.receipt(USER,row);assertNull(receipt.get("analysisTask"));assertEquals(false,receipt.get("canReconcile"));
        assertFalse(receipt.containsKey("trusted_request_json"));assertFalse(receipt.containsKey("scope_hash"));
    }
    @Test void malformedSubmittedRequestEndsRecoveryWithoutExposingOrRebuildingTask() {
        var request=new AnalysisContract.Request("A",REPORT,"解释制度",AnalysisContract.Mode.KNOWLEDGE_ONLY,null);var row=ready(request);row.put("status","SUBMITTED");row.put("analysis_run_id",RUN);
        row.put("trusted_request_json","{\"mode\":7}");when(receipts.historyReady(USER,"conversation-test")).thenReturn(true);
        var receipt=service.receipt(USER,row);assertEquals("SUBMITTED",receipt.get("status"));assertEquals(false,receipt.get("canReconcile"));assertEquals(true,receipt.get("historyReady"));
        assertNull(receipt.get("analysisTask"));assertFalse(JsonUtil.toJson(receipt).contains("trusted_request_json"));assertFalse(JsonUtil.toJson(receipt).contains(REPORT));
        verifyNoInteractions(analyses,conversations);verify(receipts,never()).submitted(any(),any(),any());
    }
}
