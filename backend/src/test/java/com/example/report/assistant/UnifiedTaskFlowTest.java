package com.example.report.assistant;

import com.example.report.common.*;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.*;
import com.example.report.entity.*;
import com.example.report.mcp.BusinessMcpClient;
import com.example.report.permission.*;
import com.example.report.semantic.*;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import java.util.*;
import java.util.function.*;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 完整查询/核验/准备/刷新流程的内存回归；生产编排与快照状态机真实运行，不创建数据库、不调用外部执行。 */
class UnifiedTaskFlowTest {
    private final DispatchHarness h=new DispatchHarness().put(SALES,candidate(SALES,"1","SO2026001","A","服务器"),candidate(SALES,"2","SO2026002","A","交换机"));
    private final DialogueState state=new DialogueState();
    private final DialogueStore store=mock(DialogueStore.class);
    private final DialogueStore.Session session=mock(DialogueStore.Session.class);
    private final ConversationService conversations=mock(ConversationService.class);
    private final BusinessMcpClient client=mock(BusinessMcpClient.class);
    private final PermissionService permissions=mock(PermissionService.class);
    private final SemanticConversationService service;
    private final String id="task-flow";
    private BiFunction<String,DialogueState,AssistantPlan> next;
    private int planningCalls;
    private Runnable afterReview=()->{};

    @SuppressWarnings("unchecked")
    UnifiedTaskFlowTest() {
        when(store.acquire(USER1,id)).thenReturn(session);when(store.timeoutSeconds()).thenReturn(180);when(session.state()).thenReturn(state);
        when(session.fenced(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        var conversation=new AgentConversation();conversation.setId(id);conversation.setTitle("任务链");
        when(conversations.getOwned(USER1,id)).thenReturn(conversation);when(conversations.messages(USER1,id,null,16)).thenReturn(List.of());
        ObjectProvider<BusinessMcpClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(client);
        when(permissions.resolve(USER1.userId())).thenReturn(USER1);
        when(client.call(eq("business_query"),eq(USER1),anyMap(),any(com.fasterxml.jackson.core.type.TypeReference.class))).thenAnswer(call->{
            BusinessQuery query=(BusinessQuery)((Map<?,?>)call.getArgument(2)).get("query");
            var columns=BusinessFields.forDomain(BusinessQuery.Domain.REPORT,List.of(new com.example.report.catalog.query.FieldInfo("productName","string","产品名称")));
            var rows=new ArrayList<Map<String,Object>>();
            for(var candidate:h.data.get(SALES)) {
                var row=new LinkedHashMap<String,Object>();row.put("rowKey",candidate.key());row.put("reportId",SALES);row.put("recordId",candidate.recordId());
                row.put("companyCode",candidate.companyCode());row.put("docNo",candidate.docNo());row.put("productName",candidate.label());row.put("status","未派单");
                if(query.view()==BusinessQuery.View.ELIGIBILITY)row.put("eligibility",new DispatchEligibility(true,"满足当前规则","1","金额规则",1,"金额大于20元",List.of()));
                rows.add(row);
            }
            return BusinessQueryEngine.execute(query,columns,rows,"内存业务事实");
        });
        AssistantPlanner planner=new AssistantPlanner(){
            @Override public IntentParser.Source source(){return IntentParser.Source.MOCK;}
            @Override public AssistantPlan plan(String message,DialogueState current,List<com.example.report.catalog.CatalogEntry> reports,Set<String> companies){planningCalls++;return next.apply(message,current);}
            @Override public AssistantPlan plan(String message,DialogueState current,List<com.example.report.catalog.CatalogEntry> reports,Set<String> companies,AssistantPlanningContext context) {
                var plan=AssistantPlanner.super.plan(message,current,reports,companies,context);afterReview.run();return plan;
            }
        };
        var assistant=new BusinessAssistantService(planner,h.catalogService,provider,conversations,permissions);
        service=new SemanticConversationService(new IntentCodec(),store,new SemanticPlanner(h.catalogService),conversations,h.previews,h.plans,h.store.plans(),h.catalogService,h.quotas,h.props,assistant);
    }
    private List<String> turn(String message,BiFunction<String,DialogueState,AssistantPlan> factory) {
        return turn(message,null,List.of(),factory);
    }
    private List<String> turn(String message,String uiPreviewId,List<RecordKey> uiExcludes,BiFunction<String,DialogueState,AssistantPlan> factory) {
        next=factory;int before=planningCalls;
        var events=service.chat(USER1,id,message,uiPreviewId,uiExcludes,"test-only").collectList().block(Duration.ofSeconds(10));
        assertNotNull(events);assertEquals(before+1,planningCalls,"执行层不能再次解析同一原文");
        // 响应流会把未捕获异常转成error事件；测试回调中的断言不能因此被done事件掩盖。
        assertFalse(events.stream().anyMatch(e->"error".equals(e.event())),JsonUtil.toJson(events.stream().map(e->e.data()).toList()));
        assertTrue(events.stream().anyMatch(e->"done".equals(e.event())));return events.stream().map(e->e.event()).toList();
    }
    private AssistantPlan dispatch(String message,DialogueState s,DispatchDirective.Source source,Action action) {
        String ref=switch(source){case QUERY_ROWS,QUERY_ALL->AssistantReferences.queryRef(s);case PREVIEW->AssistantReferences.previewRef(s);case PLAN->AssistantReferences.planRef(s);default->null;};
        return AssistantPlan.dispatch(new DispatchDirective(new SemanticIntent(1,action,List.of(),List.of(),Clarify.NONE),source,ref,source==DispatchDirective.Source.QUERY_ROWS?List.of("row-1"):List.of(),message));
    }
    private void queryServer() {
        var q=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(SALES),"A",
                List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("productName","EQ",List.of("服务器"))))),null,false,1,20,null);
        var events=turn("查一下销售报表服务器的数据",(m,s)->new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,q,false,null));
        assertTrue(events.contains("business_query"));assertFalse(events.contains("preview"));assertFalse(events.contains("plan"));
        assertEquals("1",state.getBusinessReferences().get(0).get("recordId"));
    }
    @Test void clarificationAndPlanningFailureKeepReasonsAndRecoverAfterSuccessfulQuery() {
        turn("这条指哪一条",(m,s)->new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请指定要查询的记录"));
        assertEquals(DialogueState.Phase.CLARIFY,state.getPhase());assertTrue(state.isUnresolvedRequest());
        assertEquals("请指定要查询的记录",state.getLastReason());assertNull(state.getPreviewId());
        verify(session).record(anyString(),eq("这条指哪一条"),isNull(),eq("ASSISTANT_CLARIFY"),eq("请指定要查询的记录"),eq("test-only"),anyLong());
        queryServer();assertEquals(DialogueState.Phase.READY,state.getPhase());assertFalse(state.isUnresolvedRequest());assertNull(state.getLastReason());
        var before=state.getBusinessReferences();
        var events=turn("再次查询",(m,s)->{throw new ApiException(503,"业务来源暂不可用");});
        assertFalse(events.contains("business_query"));assertEquals(before,state.getBusinessReferences());
        assertEquals(DialogueState.Phase.REJECTED,state.getPhase());assertEquals("业务来源暂不可用",state.getLastReason());
        queryServer();assertEquals(DialogueState.Phase.READY,state.getPhase());assertFalse(state.isUnresolvedRequest());assertNull(state.getLastReason());
    }
    @Test void queryEligibilityAndExplicitDispatchEndAtOnePendingItem() {
        queryServer();
        var q=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.ELIGIBILITY,List.of(SALES),"A",
                List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of("1"))))),null,false,1,20,null);
        var checked=turn("这条数据符合派单条件吗",(m,s)->new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,q,true,null));
        assertFalse(checked.contains("plan"));assertNull(state.getPreviewId());
        var prepared=turn("帮我派单这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        assertTrue(prepared.contains("preview"));assertTrue(prepared.contains("plan"));
        var plan=h.plans.getOwned(USER1,state.getPlanId());assertEquals("PENDING",plan.plan().getStatus());
        assertEquals(List.of("1"),plan.items().stream().map(i->i.getRecordId()).toList());assertEquals(2,h.data.get(SALES).size());
        verify(conversations).logToolCall(eq(id),eq(USER1.userId()),eq("dispatch_targets"),any());
    }
    @Test void directPreparationRefreshAndAnotherPreparationNeverExpandTheExactTargets() {
        queryServer();turn("就安排刚查的这笔",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        String oldPlan=state.getPlanId();
        turn("重新核对当前候选",(m,s)->dispatch(m,s,DispatchDirective.Source.PREVIEW,Action.PREVIEW));
        var preview=h.previews.getOwned(USER1,state.getPreviewId());assertEquals(List.of("1"),preview.candidates().stream().map(c->c.recordId()).toList());
        assertEquals("EXPIRED",h.store.plans().find(oldPlan).orElseThrow().getStatus());
        turn("整理成清单",(m,s)->dispatch(m,s,DispatchDirective.Source.PREVIEW,Action.PREPARE_DISPATCH));
        assertEquals(1,h.plans.getOwned(USER1,state.getPlanId()).plan().getItemCount());
        turn("撤销刚才这份",(m,s)->dispatch(m,s,DispatchDirective.Source.PLAN,Action.CANCEL_PLAN));
        assertEquals("CANCELLED",h.plans.getOwned(USER1,state.getPlanId()).plan().getStatus(),JsonUtil.toJson(state));
    }
    @Test void interruptedCandidatesMustBeRedisplayedBeforeAnotherPreparation() {
        queryServer();turn("准备这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        String original=state.getPlanId(),preview=state.getPreviewId();
        queryServer();assertTrue(state.isBusinessQueryAfterPreview());
        var refused=turn("剩下的准备清单",(m,s)->dispatch(m,s,DispatchDirective.Source.PREVIEW,Action.PREPARE_DISPATCH));
        assertFalse(refused.contains("plan"));assertFalse(refused.contains("preview"));
        assertEquals(original,state.getPlanId());assertEquals(preview,state.getPreviewId());
        assertTrue(state.getLastReason().contains("核对候选"));
        turn("返回原候选并重新核对",(m,s)->dispatch(m,s,DispatchDirective.Source.PREVIEW,Action.PREVIEW));
        assertFalse(state.isBusinessQueryAfterPreview());
        assertTrue(turn("为已核对的候选准备清单",(m,s)->dispatch(m,s,DispatchDirective.Source.PREVIEW,Action.PREPARE_DISPATCH)).contains("plan"));
    }
    @Test void aggregateFocusCannotSupplyDispatchTargetsButARecordListCanRecover() {
        var summary=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.SUMMARY,List.of(SALES),"A",List.of(),null,false,1,20,null);
        turn("销售记录总额",(m,s)->new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,summary,false,null));
        assertFalse((Boolean)AssistantReferences.queryContext(state).get("available"));
        assertTrue(((List<?>)AssistantReferences.queryContext(state).get("rows")).isEmpty());
        var refused=turn("刚才那些准备清单",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ALL,Action.PREPARE_DISPATCH));
        assertFalse(refused.contains("plan"));assertFalse(refused.contains("preview"));assertNull(state.getPlanId());assertNull(state.getPreviewId());
        queryServer();assertTrue(turn("准备这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH)).contains("plan"));
    }
    @Test void targetFailureKeepsOriginalPendingPlanAndReportsNoNewSuccess() {
        queryServer();turn("准备这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        String original=state.getPlanId(),preview=state.getPreviewId();h.put(SALES,candidate(SALES,"2","SO2026002","A","交换机"));
        var events=turn("再为刚查询的对象准备一份",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        assertFalse(events.contains("plan"));assertEquals(original,state.getPlanId());assertEquals(preview,state.getPreviewId());
        assertEquals("PENDING",h.store.plans().find(original).orElseThrow().getStatus());assertTrue(state.getLastReason().contains("目标已变化"));
    }
    @Test void explicitNewTaskDoesNotInheritPriorCompanyOrExclusions() {
        queryServer();turn("准备这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        state.setExcludedRecords(List.of(new RecordKey(SALES,"1")));
        var events=turn("从头查询全部可派报表",(m,s)->AssistantPlan.dispatch(new DispatchDirective(new SemanticIntent(1,Action.PREVIEW,
                List.of(new ScopeChange(Target.REPORTS,Operation.CLEAR,List.of(),m)),List.of(),Clarify.NONE),DispatchDirective.Source.EXPLICIT_SCOPE,null,List.of(),m)));
        assertTrue(events.contains("preview"));assertNull(state.getDesired().companyCode());assertTrue(state.getDesired().allReports());
        assertTrue(state.getExcludedRecords().isEmpty());assertEquals(2,h.previews.getOwned(USER1,state.getPreviewId()).preview().getTotalCount());
    }
    @Test void explicitRestoreRepairsStaleExclusionsWithActiveOrExpiredPreview() {
        for(boolean expired:List.of(false,true)) {
            queryServer();turn("准备这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
            state.setExcludedRecords(List.of(new RecordKey(SALES,"missing")));state.setUnresolvedRecords(true);
            if(expired)h.store.updatePreview(state.getPreviewId(),p->p.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(1)));
            var events=turn("恢复全部记录并生成清单",(m,s)->AssistantPlan.dispatch(new DispatchDirective(
                    new SemanticIntent(1,Action.PREPARE_DISPATCH,List.of(new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),m)),List.of(),Clarify.NONE),
                    DispatchDirective.Source.PREVIEW,AssistantReferences.previewRef(s),List.of(),m)));
            assertTrue(events.contains("plan"),Objects.toString(state.getLastReason()));assertTrue(state.getExcludedRecords().isEmpty());
            assertFalse(state.isUnresolvedRecords());assertEquals(1,h.plans.getOwned(USER1,state.getPlanId()).plan().getItemCount());
        }
    }
    @Test void incrementalSelectionCannotDiscardUnresolvedExclusions() {
        queryServer();turn("准备这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        String original=state.getPlanId();var stale=new RecordKey(SALES,"missing");state.setExcludedRecords(List.of(stale));
        var events=turn("排除SO2026001",(m,s)->AssistantPlan.dispatch(new DispatchDirective(
                new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("SO2026001"),m)),List.of(),Clarify.NONE),
                DispatchDirective.Source.PREVIEW,AssistantReferences.previewRef(s),List.of(),m)));
        assertFalse(events.contains("preview"));assertFalse(events.contains("plan"));assertEquals(List.of(stale),state.getExcludedRecords());
        assertEquals("PENDING",h.store.plans().find(original).orElseThrow().getStatus());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void planCreatedInUiReplacesPriorPlanReferenceBeforePlanning(boolean alreadyCancelledInUi) {
        queryServer();turn("准备这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        String old=state.getPlanId();
        var uiPlan=h.plans.create(USER1,id,state.getPreviewId(),List.of(),"ui-request").plan();
        h.store.updatePlan(old,p->p.setCreatedAt(uiPlan.getCreatedAt()));
        assertNotEquals(old,uiPlan.getId());
        if(alreadyCancelledInUi)h.plans.cancel(USER1,uiPlan.getId());
        turn("撤销当前清单",(m,s)->{assertEquals(uiPlan.getId(),s.getPlanId());return dispatch(m,s,DispatchDirective.Source.PLAN,Action.CANCEL_PLAN);});
        assertEquals("CANCELLED",h.store.plans().find(uiPlan.getId()).orElseThrow().getStatus(),JsonUtil.toJson(state));
    }
    @Test void permissionChangeDuringReviewDoesNotPublishCachedQueryOrReferences() {
        afterReview=()->when(permissions.resolve(USER1.userId())).thenReturn(new CurrentUser(USER1.tenantId(),USER1.userId(),"权限已变更",Set.of(),Set.of(),false));
        var q=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(SALES),"A",List.of(),null,false,1,20,null);
        var events=turn("查询销售报表",(m,s)->new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,q,false,null));
        assertFalse(events.contains("business_query"));assertTrue(state.isBusinessUnresolved());assertTrue(state.getBusinessReferences().isEmpty());
        verify(conversations,never()).logCard(eq(id),eq(USER1.userId()),eq("business_query"),any(),any(),any());
    }
    @Test void staleUiSelectionIsRejectedBeforeRefreshingExpiredPreview() {
        queryServer();turn("准备这条",(m,s)->dispatch(m,s,DispatchDirective.Source.QUERY_ROWS,Action.PREPARE_DISPATCH));
        String oldPreview=state.getPreviewId(),oldPlan=state.getPlanId();
        h.store.updatePreview(oldPreview,p->p.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(1)));
        var events=turn("重新核对候选","stale-ui-preview",List.of(new RecordKey(SALES,"1")),(m,s)->dispatch(m,s,DispatchDirective.Source.PREVIEW,Action.PREVIEW));
        assertFalse(events.contains("preview"));assertEquals(oldPreview,state.getPreviewId());assertEquals(oldPlan,state.getPlanId());
        assertEquals("ACTIVE",h.store.previews().find(oldPreview).orElseThrow().getStatus());
        assertEquals("PENDING",h.store.plans().find(oldPlan).orElseThrow().getStatus());
        assertEquals(1,h.store.previews().byConversation(id).size());
    }
    @SuppressWarnings("unchecked")
    @Test void responseCannotExpandAnExplicitCompanyEvenForAnAdministrator() {
        var q=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(SALES),"A",List.of(),null,false,1,20,null);
        var response=new BusinessResult(q,"2026-10-08T12:00:00+08:00","业务事实",List.of(),List.of(Map.of("reportId",SALES,"recordId","1","companyCode","B")),1,
                new BusinessResult.Summary(1,Map.of(),Map.of(),Map.of(),Map.of(),0));
        when(client.call(eq("business_query"),eq(ADMIN),anyMap(),any(com.fasterxml.jackson.core.type.TypeReference.class))).thenReturn(response);
        ObjectProvider<BusinessMcpClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(client);
        var assistant=new BusinessAssistantService(mock(AssistantPlanner.class),h.catalogService,provider,conversations,permissions);
        assertEquals(502,assertThrows(ApiException.class,()->assistant.read(ADMIN,q)).getCode());
    }
}
