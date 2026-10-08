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
    private final SemanticConversationService service;
    private final String id="task-flow";
    private BiFunction<String,DialogueState,AssistantPlan> next;
    private int planningCalls;

    @SuppressWarnings("unchecked")
    UnifiedTaskFlowTest() {
        when(store.acquire(USER1,id)).thenReturn(session);when(store.timeoutSeconds()).thenReturn(180);when(session.state()).thenReturn(state);
        when(session.fenced(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        var conversation=new AgentConversation();conversation.setId(id);conversation.setTitle("任务链");
        when(conversations.getOwned(USER1,id)).thenReturn(conversation);when(conversations.messages(USER1,id,null,16)).thenReturn(List.of());
        ObjectProvider<BusinessMcpClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(client);
        var permissions=mock(PermissionService.class);when(permissions.resolve(USER1.userId())).thenReturn(USER1);
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
        };
        var assistant=new BusinessAssistantService(planner,h.catalogService,provider,conversations,permissions);
        service=new SemanticConversationService(new IntentCodec(),store,new SemanticPlanner(h.catalogService),conversations,h.previews,h.plans,h.store.plans(),h.catalogService,h.quotas,h.props,assistant);
    }
    private List<String> turn(String message,BiFunction<String,DialogueState,AssistantPlan> factory) {
        next=factory;int before=planningCalls;
        var events=service.chat(USER1,id,message,null,List.of(),"test-only").collectList().block(Duration.ofSeconds(10));
        assertNotNull(events);assertEquals(before+1,planningCalls,"执行层不能再次解析同一原文");
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
        assertEquals("CANCELLED",h.plans.getOwned(USER1,state.getPlanId()).plan().getStatus());
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
}
