package com.example.report.semantic;

import com.example.report.agent.AgentChatService;
import com.example.report.assistant.*;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.*;
import com.example.report.operations.DataRetentionService;
import com.example.report.permission.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.codec.ServerSentEvent;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用隔离MySQL库验证对话、预览、选择恢复与待确认清单；注入完整任务及复核结论，语义理解另由模型测试验证。 */
@EnabledIfEnvironmentVariable(named="P2_IT",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"agent.semantic.mode=active","agent.retention-sweep-ms=3600000","spring.data.redis.database=15"})
@ActiveProfiles("mock") @DirtiesContext
class SemanticIntegrationTest {
    @Test void freshExplicitReportQueryRecoversAfterFailedSelectionWithoutApplyingItsDraft() {
        String id=conversation();turn(id,"A公司销售报表的");
        String previous="先不选SO2026001";
        var accepted=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,
                SemanticIntent.Operation.EXCLUDE,List.of("SO2026001"),previous,List.of(),SemanticIntent.SelectorKind.DOCUMENT,SemanticIntent.Quantifier.ONE)),List.of(),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(accepted).when(parser).parse(org.mockito.ArgumentMatchers.eq(previous),org.mockito.ArgumentMatchers.any());
        turn(id,previous);assertEquals(1,store.read(user(),id).getExcludedRecords().size());
        assertEquals(accepted.scopeChanges(),store.read(user(),id).getLastSuccessfulSelection());
        var references=store.read(user(),id).getLastSelectionReferences();assertEquals("SO2026001",references.get(0).get("docNo"));
        assertTrue(store.read(user(),id).isLastSelectionReferencesComplete());assertFalse(references.get(0).containsKey("operation"));
        String bad="排除单据DOES-NOT-EXIST";
        var failed=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,
                SemanticIntent.Operation.EXCLUDE,List.of("DOES-NOT-EXIST"),bad,List.of(),SemanticIntent.SelectorKind.DOCUMENT,SemanticIntent.Quantifier.ONE)),List.of(),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(failed).when(parser).parse(org.mockito.ArgumentMatchers.eq(bad),org.mockito.ArgumentMatchers.any());
        turn(id,bad);assertTrue(store.read(user(),id).isUnresolvedRequest());
        assertFalse(store.read(user(),id).isUnresolvedRecords(),"预检拒绝的草稿不能改写已成功的选择状态");
        assertEquals(accepted.scopeChanges(),store.read(user(),id).getLastSuccessfulSelection());
        String readMessage="先查询工单再处理派单";
        var businessQuery=new com.example.report.assistant.BusinessQuery(com.example.report.assistant.BusinessQuery.Domain.WORK_ORDER,
                com.example.report.assistant.BusinessQuery.View.LIST,List.of("rpt-sales-order"),"A",List.of(),null,false,1,20,null);
        org.mockito.Mockito.doReturn(new com.example.report.assistant.AssistantPlan(com.example.report.assistant.AssistantPlan.Route.BUSINESS_QUERY,businessQuery,false,null))
                .when(businessPlanner).plan(org.mockito.ArgumentMatchers.eq(readMessage),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anySet());
        org.mockito.Mockito.doReturn(new com.example.report.assistant.BusinessResult(businessQuery,"2026-10-07T10:00:00+08:00","测试事实",List.of(),List.of(),0,
                new com.example.report.assistant.BusinessResult.Summary(0,Map.of(),Map.of(),Map.of(),Map.of(),0)))
                .when(businessAssistant).read(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(businessQuery));
        turn(id,readMessage);assertTrue(store.read(user(),id).isBusinessQueryAfterPreview());
        String fresh="重新查询费用报表可派的";
        var query=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(new SemanticIntent.ScopeChange(SemanticIntent.Target.REPORTS,
                SemanticIntent.Operation.REPLACE,List.of("费用报表"),fresh)),List.of(),SemanticIntent.Clarify.NONE);
        task(fresh,s->AssistantPlan.dispatch(new DispatchDirective(query,DispatchDirective.Source.EXPLICIT_SCOPE,null,List.of(),fresh)));
        var events=turn(id,fresh);assertTrue(events.stream().anyMatch(e->"preview".equals(e.event())),text(events));
        var state=store.read(user(),id);assertFalse(state.isUnresolvedRecords());assertEquals(DialogueState.Phase.READY,state.getPhase());
        assertEquals(List.of(),state.getExcludedRecords());assertEquals(List.of("rpt-expense-claim"),state.getEffective().reportIds());
        assertEquals(List.of(),state.getLastSuccessfulSelection());
        assertEquals(List.of(),state.getLastSelectionReferences());
    }
    static final String SCHEMA="semantic_it_"+UUID.randomUUID().toString().replace("-","");
    static volatile String rateTenant=SCHEMA;
    @org.springframework.boot.test.context.TestConfiguration
    static class IsolatedQuotas {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        com.example.report.config.ResourceQuotaService isolatedSemanticQuotas(
                org.springframework.data.redis.core.StringRedisTemplate redis,com.example.report.config.QuotaLeaseStore leases) {
            return new com.example.report.config.ResourceQuotaService(redis,leases) {
                @Override public Permit acquire(CurrentUser user,String operation,Collection<String> reportIds) {
                    // Keep real Redis/MySQL enforcement, but each test owns its rate namespace.
                    return super.acquire(new CurrentUser(rateTenant,user.userId(),user.displayName(),user.companies(),user.permissions(),user.admin()),operation,reportIds);
                }
            };
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:mysql://"+System.getenv().getOrDefault("TRACE_DB_HOST","127.0.0.1")+":"+System.getenv().getOrDefault("TRACE_DB_PORT","3306")+"/"+SCHEMA+"?createDatabaseIfNotExist=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("TRACE_DB_USER","root"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("TRACE_DB_PASSWORD",""));
    }
    @Autowired DialogueStore store;
    @Autowired SemanticConversationService semantic;
    @Autowired AgentChatService chat;
    @Autowired ConversationService conversations;
    @Autowired PermissionService permissions;
    @Autowired PreviewService previews;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataRetentionService retention;
    @Autowired org.springframework.transaction.support.TransactionOperations tx;
    @Autowired AgentProperties props;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ModelIntentParser parser;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.example.report.support.TestAssistantPlanner businessPlanner;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.example.report.assistant.BusinessAssistantService businessAssistant;
    @Autowired com.example.report.assistant.BusinessHistoryAccess businessHistory;
    static JdbcTemplate cleanup;
    @BeforeEach void cleanupHandle(){cleanup=jdbc;rateTenant=SCHEMA+"_"+UUID.randomUUID().toString().substring(0,8);}
    @AfterAll static void drop(){if(cleanup!=null && SCHEMA.matches("semantic_it_[a-f0-9]{32}")){
        assertEquals(SCHEMA,cleanup.queryForObject("SELECT DATABASE()",String.class));cleanup.execute("DROP DATABASE `"+SCHEMA+"`");}}
    CurrentUser user(){return permissions.resolve("user1");}
    String conversation(){return conversations.create(user(),"test").getId();}
    List<ServerSentEvent<Object>> turn(String id,String message){return chat.chat(user(), id, message, null, List.of()).collectList().block(Duration.ofSeconds(30));}
    String text(List<ServerSentEvent<Object>> events){return events.stream().filter(e->"text".equals(e.event())||"error".equals(e.event())).map(e->JsonUtil.toJson(e.data())).reduce("",String::concat);}
    /** 在统一任务边界注入固定复核结果；生产预检、状态持久化及执行约束仍真实运行，不推断自然语言。 */
    private void task(String message,java.util.function.Function<DialogueState,AssistantPlan> result) {
        org.mockito.Mockito.doAnswer(call->result.apply(call.getArgument(1))).when(businessPlanner)
                .plan(org.mockito.ArgumentMatchers.eq(message),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anySet());
    }
    /** 固定的歧义复核结论用于验证拒绝后的无副作用边界；真实复核能力由ModelAssistantPlannerTest单独覆盖。 */
    private void unresolvedContinuation(String message) {
        task(message,s->{
            assertTrue(s.isUnresolvedRequest() || s.isBusinessQueryAfterPreview(),"必须把前轮失败或话题切换交给统一规划器");
            return new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"本轮目标尚未确定，请明确公司、报表或是否继续原候选");
        });
    }
    @Test void businessQueryKeepsDispatchPreviewSelectionAndPendingPlanUnchanged() {
        String id=conversation();turn(id,"A公司销售报表的");
        var before=store.read(user(),id);String preview=before.getPreviewId();
        var pending=plans.create(user(),id,preview,List.of(),"business-guard:"+id);
        var query=new com.example.report.assistant.BusinessQuery(com.example.report.assistant.BusinessQuery.Domain.WORK_ORDER,com.example.report.assistant.BusinessQuery.View.LIST,List.of("rpt-sales-order"),"A",List.of(),null,false,1,20,null);
        var planned=new com.example.report.assistant.AssistantPlan(com.example.report.assistant.AssistantPlan.Route.BUSINESS_QUERY,query,false,null);
        org.mockito.Mockito.doReturn(planned).when(businessPlanner).plan(org.mockito.ArgumentMatchers.eq("查询工单"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anySet());
        var result=new com.example.report.assistant.BusinessResult(query,"2026-10-06T12:00:00+08:00","程序测试事实",List.of(),List.of(Map.of("rowKey","wo1","reportId","rpt-sales-order","companyCode","A","orderId","WO-1")),1,
                new com.example.report.assistant.BusinessResult.Summary(1,Map.of(),Map.of("待审批",1),Map.of(),Map.of(),0));
        org.mockito.Mockito.doReturn(result).when(businessAssistant).read(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(query));
        long plansBefore=jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan",Long.class);
        var events=turn(id,"查询工单");assertTrue(events.stream().anyMatch(e->"business_query".equals(e.event())),text(events));
        var after=store.read(user(),id);assertEquals(preview,after.getPreviewId());assertEquals(before.getDesired(),after.getDesired());assertEquals(before.getExcludedRecords(),after.getExcludedRecords());
        assertEquals(plansBefore,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan",Long.class));assertEquals(query,after.getBusinessQuery());
        assertEquals("PENDING",plans.getOwned(user(),pending.plan().getId()).plan().getStatus());
        assertEquals("WO-1",after.getBusinessReferences().get(0).get("orderId"));assertEquals("1",after.getBusinessReferences().get(0).get("displayIndex"));
        // 事实先写可靠事件，展示投影可能被前一条待投递消息阻挡；验证有界最终恢复，不能只检查SSE卡片。
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->
                assertTrue(conversations.messages(user(),id,null,100).stream().anyMatch(m->"business_query".equals(m.cardType())),
                        ()->jdbc.queryForList("SELECT event_type,delivery_status FROM trace_event WHERE conversation_id=?",id).toString()));
        var revoked=new CurrentUser(user().tenantId(),user().userId(),user().displayName(),Set.of("B"),user().permissions(),false);
        assertFalse(businessHistory.readable(revoked,id));assertTrue(businessHistory.readable(user(),id));
        var narrowAdmin=new CurrentUser(user().tenantId(),"other-admin","管理员",Set.of("B"),Set.of("*"),true);
        assertFalse(businessHistory.readable(narrowAdmin,id));
        var fullAdmin=new CurrentUser(user().tenantId(),"other-admin","管理员",Set.of("A"),Set.of("*"),true);
        assertTrue(businessHistory.readable(fullAdmin,id));
        assertThrows(ApiException.class,()->previews.requireConversationReadable(narrowAdmin,id));
        unresolvedContinuation("剩下的帮我派单吧");
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
        assertEquals(plansBefore,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan",Long.class));
        // 历史读取需要当前业务权限，但仅删除本人会话不应被已撤销的业务范围阻挡。
        conversations.softDelete(revoked,id);assertThrows(ApiException.class,()->conversations.getOwned(user(),id));
    }
    @Test void businessRoutingFailureCannotFallThroughToDispatchOrOverwriteSelection() {
        String id=conversation();turn(id,"A公司销售报表的");var before=store.read(user(),id);
        org.mockito.Mockito.doThrow(new ApiException(422,"bad model")).when(businessPlanner).plan(org.mockito.ArgumentMatchers.eq("查询未知业务"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anySet());
        var events=turn(id,"查询未知业务");assertFalse(events.stream().anyMatch(e->Set.of("preview","plan","business_query").contains(e.event())));
        assertTrue(text(events).contains("未应用任何修改"));
        var after=store.read(user(),id);assertEquals(before.getPreviewId(),after.getPreviewId());assertEquals(before.getDesired(),after.getDesired());
        assertTrue(after.isUnresolvedRequest());assertEquals(before.isBusinessUnresolved(),after.isBusinessUnresolved());assertEquals("DISPATCH",after.getAssistantFocus());
        assertEquals("bad model",after.getLastReason());
        assertEquals("bad model",jdbc.queryForObject("SELECT reason FROM semantic_turn WHERE conversation_id=? AND utterance=?",String.class,id,"查询未知业务"));
    }
    @Test void failedBusinessReadAlsoRequiresFreshDispatchPreviewBeforeImplicitPreparation() {
        String id=conversation();turn(id,"A公司销售报表的");var before=store.read(user(),id);
        var query=new com.example.report.assistant.BusinessQuery(com.example.report.assistant.BusinessQuery.Domain.WORK_ORDER,com.example.report.assistant.BusinessQuery.View.LIST,List.of("rpt-sales-order"),"A",List.of(),null,false,1,20,null);
        var plan=new com.example.report.assistant.AssistantPlan(com.example.report.assistant.AssistantPlan.Route.BUSINESS_QUERY,query,false,null);
        org.mockito.Mockito.doReturn(plan).when(businessPlanner).plan(org.mockito.ArgumentMatchers.eq("查看工单"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anySet());
        org.mockito.Mockito.doThrow(new ApiException(503,"来源暂不可用")).when(businessAssistant).read(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(query));
        var failed=turn(id,"查看工单");assertFalse(failed.stream().anyMatch(e->"business_query".equals(e.event())));
        var state=store.read(user(),id);assertEquals(before.getPreviewId(),state.getPreviewId());assertNull(state.getBusinessQuery());assertTrue(state.isBusinessQueryAfterPreview());
        String unsupportedMessage="直接准备这个清单";
        unresolvedContinuation(unsupportedMessage);
        var explained=turn(id,unsupportedMessage);assertTrue(text(explained).contains("目标尚未确定"),text(explained));
        assertEquals(before.getPreviewId(),store.read(user(),id).getPreviewId());
        unresolvedContinuation("剩下的帮我派单吧");
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
        assertTrue(turn(id,"查一下我有哪些可以派单").stream().anyMatch(e->"preview".equals(e.event())));
        assertFalse(store.read(user(),id).isBusinessQueryAfterPreview());
        org.mockito.Mockito.doCallRealMethod().when(businessPlanner).plan(org.mockito.ArgumentMatchers.eq("剩下的帮我派单吧"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anySet());
        assertTrue(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
    }
    @Test void reportedConversationReplaysWithoutInventedPermissionOrScope() {
        String id=conversation();
        var a=turn(id,"查一下A公司有哪些可以派单");
        assertTrue(a.stream().anyMatch(e->"preview".equals(e.event())),text(a));
        String first=store.read(user(),id).getPreviewId();
        var b=turn(id,"查一下B公司有哪些可以派单");
        assertTrue(text(b).contains("无权查看 B 公司"),text(b));
        assertFalse(b.stream().anyMatch(e->"preview".equals(e.event())));
        var rejected=store.read(user(),id);
        assertEquals("A",rejected.getDesired().companyCode());assertEquals("A",rejected.getEffective().companyCode());
        assertEquals(DialogueState.Phase.REJECTED,rejected.getPhase());assertTrue(rejected.isUnresolvedRequest());
        assertTrue(rejected.getLastReason().contains("无权查看 B 公司"));
        unresolvedContinuation("现在我只想派销售报表的");
        var inherited=turn(id,"现在我只想派销售报表的");
        assertTrue(text(inherited).contains("目标尚未确定"),text(inherited));
        assertFalse(inherited.stream().anyMatch(e->"plan".equals(e.event())));
        assertEquals(first,store.read(user(),id).getPreviewId());
        var corrected=turn(id,"A公司销售报表的");
        assertTrue(corrected.stream().anyMatch(e->"preview".equals(e.event())),text(corrected));
        var state=store.read(user(),id);assertEquals("A",state.getDesired().companyCode());
        assertEquals(List.of("rpt-sales-order"),state.getDesired().reportIds());
        assertTrue(previews.getOwned(user(),state.getPreviewId()).candidates().stream().allMatch(r->"A".equals(r.companyCode())));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM semantic_turn WHERE conversation_id=?",Integer.class,id));
    }
    @Test void selectionSurvivesReloadAndPreparationNeverExecutes() {
        String id=conversation();turn(id,"A公司销售报表的");
        String preview=store.read(user(),id).getPreviewId();
        var selection=turn(id,"排除SO2026002");
        assertFalse(selection.stream().anyMatch(e->"preview".equals(e.event())),text(selection));
        var restored=new DialogueStore(jdbc,tx,props).read(user(),id);
        assertEquals(preview,restored.getPreviewId());assertEquals(1,restored.getExcludedRecords().size());
            var events=turn(id,"剩下的帮我派单吧");
            assertTrue(events.stream().anyMatch(e->"plan".equals(e.event())),text(events));
            var state=store.read(user(),id);var plan=plans.getOwned(user(),state.getPlanId());
            assertEquals("PENDING",plan.plan().getStatus());
            assertTrue(plan.candidates().stream().noneMatch(c->"SO2026002".equals(c.docNo())));
            assertTrue(plan.items().stream().allMatch(i->i.getAttemptCount()==null || i.getAttemptCount()==0));
            assertTrue(text(turn(id,"查看派单结果")).contains("待确认"));
            assertTrue(text(turn(id,"取消清单")).contains("已取消"));
    }
    @Test void manualSelectionPersistsAndRejectsStaleOrForeignWrites() {
        String id=conversation();turn(id,"A公司销售报表的");
        String preview=store.read(user(),id).getPreviewId();
        var rows=previews.getOwned(user(),preview).candidates();
        var first=new RecordKey(rows.get(0).reportId(),rows.get(0).recordId());
        var second=new RecordKey(rows.get(1).reportId(),rows.get(1).recordId());
        semantic.updateSelection(user(),id,preview,List.of(),List.of(first));
        assertEquals(List.of(first),new DialogueStore(jdbc,tx,props).read(user(),id).getExcludedRecords());
        assertDoesNotThrow(()->semantic.updateSelection(user(),id,preview,List.of(),List.of(first)),"丢失响应后重复相同选择幂等重放");
        assertEquals(409,assertThrows(ApiException.class,()->semantic.updateSelection(user(),id,preview,List.of(),List.of(second))).getCode());
        assertThrows(ApiException.class,()->semantic.updateSelection(permissions.resolve("user2"),id,preview,List.of(first),List.of()));
        assertThrows(ApiException.class,()->semantic.updateSelection(user(),id,preview,List.of(first),List.of(first,first)));
        assertThrows(ApiException.class,()->semantic.updateSelection(user(),id,"stale",List.of(first),List.of()));
        assertThrows(ApiException.class,()->semantic.updateSelection(user(),id,preview,List.of(first),List.of(new RecordKey("foreign","1"))));
        var plan=plans.create(user(),id,preview,List.of(first),"manual-selection-test-"+id);
        assertEquals("PENDING",plan.plan().getStatus());
        assertEquals(List.of(first),semantic.selection(user(),id).get("excludedRecords"));
        assertTrue(plan.candidates().stream().noneMatch(r->r.reportId().equals(first.reportId()) && r.recordId().equals(first.recordId())));
    }
    @Test void reportQualifiedExclusionKeepsOtherReportsAndSurvivesReloadIntoPendingPlan() {
        String id=conversation();
        turn(id,"查一下我有哪些可以派单");
        var before=store.read(user(),id);
        var original=previews.getOwned(user(),before.getPreviewId());
        String message="应收报表不要天津某某贸易有限公司的";
        var intent=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(new SemanticIntent.ScopeChange(
                SemanticIntent.Target.RECORDS,SemanticIntent.Operation.ADD,List.of("天津某某贸易有限公司"),message,List.of("应收报表"))),
                List.of(),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(intent).when(parser).parse(org.mockito.ArgumentMatchers.eq(message),org.mockito.ArgumentMatchers.any());
        var events=turn(id,message);
        assertFalse(events.stream().anyMatch(e->"preview".equals(e.event())),text(events));
        assertTrue(text(events).contains("已排除 1 条"),text(events));
        var restored=new DialogueStore(jdbc,tx,props).read(user(),id);
        assertEquals(before.getPreviewId(),restored.getPreviewId());
        assertEquals(before.getDesired(),restored.getDesired());
        var excluded=original.candidates().stream().filter(r->"天津某某贸易有限公司".equals(r.label()))
                .map(r->new RecordKey(r.reportId(),r.recordId())).toList();
        assertEquals(1,excluded.size());
        assertEquals(excluded,restored.getExcludedRecords());
        assertEquals("ACTIVE",previews.getOwned(user(),before.getPreviewId()).preview().getStatus());
        var prepared=turn(id,"剩下的帮我派单吧");
        assertTrue(prepared.stream().anyMatch(e->"plan".equals(e.event())),text(prepared));
        var plan=plans.getOwned(user(),store.read(user(),id).getPlanId());
        assertEquals("PENDING",plan.plan().getStatus());
        assertEquals(original.preview().getTotalCount()-1,plan.candidates().size());
        var expected=original.candidates().stream().map(r->new RecordKey(r.reportId(),r.recordId()))
                .filter(k->!excluded.contains(k)).collect(java.util.stream.Collectors.toSet());
        assertEquals(expected,plan.candidates().stream().map(r->new RecordKey(r.reportId(),r.recordId())).collect(java.util.stream.Collectors.toSet()));
        assertTrue(plan.items().stream().allMatch(i->i.getAttemptCount()==null || i.getAttemptCount()==0));
    }
    @Test void customerIdentityAndAliasesAreFrozenAndMultipleInvoicesSurviveReload() {
        long added=901001;
        jdbc.update("INSERT INTO report_receivable(id,tenant_id,company_code,invoice_no,customer_id,customer_name,customer_aliases,amount,due_date) VALUES (?,'T001','A','MULTI-CUSTOMER','CUST-003','天津某某贸易有限公司','[\"天津某某贸易\"]',9,'2026-10-01')",added);
        try {
            String id=conversation();turn(id,"查一下我有哪些可以派单");
            var before=store.read(user(),id);var snapshot=previews.getOwned(user(),before.getPreviewId());
            assertEquals(2,snapshot.candidates().stream().filter(r -> r.counterparty()!=null && r.counterparty().id().equals("CUST-003")).count());
            jdbc.update("UPDATE report_receivable SET customer_aliases='[\"来源已改名\"]' WHERE id=?",added);
            String message="应收报表排除天津某某贸易所有记录";
            var intent=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,
                    SemanticIntent.Operation.EXCLUDE,List.of("天津某某贸易"),message,List.of("应收报表"),SemanticIntent.SelectorKind.COUNTERPARTY,SemanticIntent.Quantifier.ALL)),List.of(),SemanticIntent.Clarify.NONE);
            org.mockito.Mockito.doReturn(intent).when(parser).parse(org.mockito.ArgumentMatchers.eq(message),org.mockito.ArgumentMatchers.any());
            assertTrue(text(turn(id,message)).contains("已排除 2 条"));
            var restored=new DialogueStore(jdbc,tx,props).read(user(),id);assertEquals(2,restored.getExcludedRecords().size());
            assertEquals(before.getPreviewId(),restored.getPreviewId());
            turn(id,"剩下的帮我派单吧");
            var plan=plans.getOwned(user(),store.read(user(),id).getPlanId());
            assertEquals(snapshot.preview().getTotalCount()-2,plan.plan().getItemCount());
            assertTrue(plan.candidates().stream().noneMatch(r -> r.counterparty()!=null && r.counterparty().id().equals("CUST-003")));
            assertTrue(plan.candidates().stream().anyMatch(r -> r.counterparty()!=null));
        } finally {jdbc.update("DELETE FROM report_receivable WHERE id=?",added);}
    }
    @Test void incompleteSnapshotCannotApplyAnAllCustomerSelection() {
        String id=conversation();turn(id,"查一下我有哪些可以派单");
        var before=store.read(user(),id);
        jdbc.update("DELETE FROM dispatch_preview_item WHERE preview_id=? AND report_id='rpt-sales-order' LIMIT 1",before.getPreviewId());
        String message="应收报表排除天津某某贸易有限公司全部记录";
        var intent=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,
                SemanticIntent.Operation.EXCLUDE,List.of("天津某某贸易有限公司"),message,List.of("应收报表"),SemanticIntent.SelectorKind.COUNTERPARTY,SemanticIntent.Quantifier.ALL)),List.of(),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(intent).when(parser).parse(org.mockito.ArgumentMatchers.eq(message),org.mockito.ArgumentMatchers.any());
        var events=turn(id,message);assertTrue(text(events).contains("预览记录不完整"),text(events));
        assertTrue(store.read(user(),id).getExcludedRecords().isEmpty());
        assertFalse(events.stream().anyMatch(e -> "plan".equals(e.event())));
    }
    @Test void expiredPreviewRetainsExclusionsWithAndWithoutUiSelection() {
        for (boolean ui : List.of(false,true)) {
            String id=conversation();turn(id,"A公司销售报表的");turn(id,"排除SO2026002");
            var before=store.read(user(),id);
            jdbc.update("UPDATE dispatch_preview SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",before.getPreviewId());
            var events=chat.chat(user(), id, "剩下的帮我派单吧", ui?before.getPreviewId():null, ui?before.getExcludedRecords():List.of()).collectList().block(Duration.ofSeconds(30));
            assertTrue(events.stream().anyMatch(e->"plan".equals(e.event())),text(events));
            var state=store.read(user(),id);assertNotEquals(before.getPreviewId(),state.getPreviewId());
            assertEquals(before.getExcludedRecords(),state.getExcludedRecords());
            var plan=plans.getOwned(user(),state.getPlanId());assertEquals("PENDING",plan.plan().getStatus());
            assertTrue(plan.items().stream().noneMatch(i->"SO2026002".equals(i.getDocNo())));
            assertTrue(plan.items().stream().allMatch(i->i.getAttemptCount()==null || i.getAttemptCount()==0));
        }
    }
    @Test void scopeChangeCannotDropExclusionsAndExplicitResetCanRecover() {
        String id=conversation();turn(id,"A公司销售报表的");turn(id,"排除SO2026002");
        org.mockito.Mockito.doReturn(new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(
                new SemanticIntent.ScopeChange(SemanticIntent.Target.REPORTS,SemanticIntent.Operation.REPLACE,List.of("费用报表"),"只查费用报表")),List.of(),SemanticIntent.Clarify.NONE))
                .when(parser).parse(org.mockito.ArgumentMatchers.eq("只查费用报表"),org.mockito.ArgumentMatchers.any());
        var changed=turn(id,"只查费用报表");
        assertTrue(store.read(user(),id).isUnresolvedRecords(),text(changed));
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
        turn(id,"恢复全部记录");
        assertFalse(store.read(user(),id).isUnresolvedRecords());
        assertTrue(store.read(user(),id).getExcludedRecords().isEmpty());
        assertTrue(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
    }
    @Test void refreshingLargePreviewWithExplicitAllSelectionDoesNotLoadAllRows() {
        int previous=props.getPreview().getMaxItems();props.getPreview().setMaxItems(2);
        try {
            String id=conversation();turn(id,"A公司销售报表的");
            String source=store.read(user(),id).getPreviewId();
            var events=chat.chat(user(), id, "A公司销售报表的", source, List.of()).collectList().block(Duration.ofSeconds(30));
            assertTrue(events.stream().anyMatch(e->"preview".equals(e.event())),text(events));
            assertEquals(DialogueState.Phase.READY,store.read(user(),id).getPhase(),text(events));
        } finally {props.getPreview().setMaxItems(previous);}
    }
    @Test void reportPaginationKeepsPermissionFiltersAndStableOrdering(@Autowired com.example.report.report.ReportService reports) {
        var first=reports.pageSales(user(),1,2);var second=reports.pageSales(user(),2,2);
        assertEquals(2,first.records().size());assertEquals(first.total(),second.total());
        assertTrue(first.total()>2);
        assertTrue(first.records().stream().allMatch(r->"A".equals(r.getCompanyCode()) && user().tenantId().equals(r.getTenantId())));
        assertTrue(second.records().stream().noneMatch(r->first.records().stream().anyMatch(x->x.getId().equals(r.getId()))));
        assertTrue(reports.pageSales(user(),100000,200).records().isEmpty());
        assertThrows(ApiException.class,()->reports.pageSales(user(),0,50));
        assertThrows(ApiException.class,()->reports.pageSales(user(),1,201));
        var nobody=new CurrentUser(user().tenantId(),"empty","empty",Set.of(),Set.of(),false);
        assertEquals(0,reports.pageSales(nobody,1,50).total());
        assertEquals(0,reports.pageReceivable(nobody,1,50).total());
        assertEquals(0,reports.pageExpense(nobody,1,50).total());
    }
    @Test void ambiguousScopeStaysBlockedUntilExplicitReportCorrection() {
        String id=conversation();turn(id,"只查销售报表");
        var ambiguous=turn(id,"查一下客户对账有哪些可以派单");
        assertTrue(text(ambiguous).contains("需要确认"),text(ambiguous));
        unresolvedContinuation("剩下的帮我派单吧");
        var again=turn(id,"剩下的帮我派单吧");
        assertFalse(again.stream().anyMatch(e->"plan".equals(e.event())));
        assertTrue(text(again).contains("尚未确定"),text(again));
        turn(id,"只查销售报表");assertFalse(store.read(user(),id).isUnresolvedReports());
    }
    @Test void crossInstanceLeaseExpiryAndErasureFenceOldWorkers() {
        String id=conversation();
        var original=store.acquire(user(),id);
        var other=new DialogueStore(jdbc,tx,props);
        assertThrows(ApiException.class,()->other.acquire(user(),id));
        assertThrows(ApiException.class,()->store.read(permissions.resolve("user2"),id));
        original.state().setDesired(new DialogueState.Scope("B",true,List.of()));original.save();
        jdbc.update("UPDATE semantic_dialogue SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE conversation_id=?",id);
        try(var winner=other.acquire(user(),id)) {
            assertEquals("B",winner.state().getDesired().companyCode());
            assertThrows(ApiException.class,original::save);
            original.close();winner.check();
            retention.request(user(),id,"测试删除语义会话");
            assertThrows(ApiException.class,winner::save);
            retention.erase(id);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM semantic_dialogue WHERE conversation_id=?",Integer.class,id));
        }
    }
    @Test void clarificationOnFirstTurnPersistsItsPhaseAndReasonWithoutExecution() {
        String id=conversation();var events=turn(id,"完全未收录的模拟输入");
        assertFalse(events.stream().anyMatch(e->Set.of("preview","plan","result").contains(e.event())));
        assertEquals(DialogueState.Phase.CLARIFY,store.read(user(),id).getPhase());
        assertEquals("请明确本轮对象及操作",store.read(user(),id).getLastReason());
        assertEquals("请明确本轮对象及操作",jdbc.queryForObject("SELECT reason FROM semantic_turn WHERE conversation_id=?",String.class,id));
    }
    @Test void parserFailureCannotBeFollowedByDispatchOfOldScope() {
        String id=conversation();turn(id,"A公司销售报表的");
        org.mockito.Mockito.doThrow(new ApiException(422,"协议无效"))
                .when(parser).parse(org.mockito.ArgumentMatchers.eq("解析故障"),org.mockito.ArgumentMatchers.any());
        turn(id,"解析故障");
        unresolvedContinuation("剩下的帮我派单吧");
        var events=turn(id,"剩下的帮我派单吧");
        assertFalse(events.stream().anyMatch(e->"plan".equals(e.event())),text(events));
        assertTrue(store.read(user(),id).isUnresolvedRequest());
        var correction=turn(id,"A公司销售报表的");
        assertTrue(correction.stream().anyMatch(e->"preview".equals(e.event())),text(correction));
    }
    @Test void expiredWorkerCannotActivatePreviewAndEraseCleansTurnEvidence() {
        String id=conversation();turn(id,"A公司销售报表的");
        String first=store.read(user(),id).getPreviewId();
        try(var session=store.acquire(user(),id)) {
            jdbc.update("UPDATE semantic_dialogue SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE conversation_id=?",id);
            assertThrows(ApiException.class,()->previews.preview(user(),id,
                    new PreviewCommand("PREVIEW", "semantic", null, List.of("rpt-sales-order"), new PreviewCommand.Filters("A"), "replace"),
                    n->{},p->session.fenced(()->null)));
            assertEquals(first,previews.latest(user(),id).orElseThrow().getId());
        }
        retention.request(user(),id,"语义证据删除测试");retention.erase(id);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM semantic_turn WHERE conversation_id=?",Integer.class,id));
        assertThrows(ApiException.class,()->store.acquire(user(),id));
    }
    @Test void modelFailureNeverFallsBackToKeywordScopeAndExplicitCorrectionCanRecover() {
        String id=conversation();
        assertTrue(turn(id,"A公司销售报表的").stream().anyMatch(e->"preview".equals(e.event())));
        org.mockito.Mockito.doThrow(new ApiException(503,"model unavailable"))
                .when(parser).parse(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        var refused=turn(id,"那 B 公司呢？");
        assertFalse(refused.stream().anyMatch(e->Set.of("preview","plan").contains(e.event())));
        assertTrue(store.read(user(),id).isUnresolvedRequest());
        org.mockito.Mockito.doCallRealMethod().when(parser).parse(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        unresolvedContinuation("剩下的帮我派单吧");
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
        var corrected=turn(id,"A公司销售报表的");
        assertTrue(corrected.stream().anyMatch(e->"preview".equals(e.event())),text(corrected));
        assertEquals(IntentParser.Source.MOCK,store.read(user(),id).getParserSource());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM semantic_turn WHERE conversation_id=? AND model='domain-grammar-v1'",Integer.class,id));
    }

    @Test void queryNegationCannotCreatePlanAndOrderedScopeChangesReachBusinessServices() {
        String id=conversation();
        var events=turn(id,"只查销售报表，不要派单");
        assertTrue(events.stream().anyMatch(e->"preview".equals(e.event())),text(events));
        assertFalse(events.stream().anyMatch(e->"plan".equals(e.event())));
        var state=store.read(user(),id);
        assertEquals(List.of("rpt-sales-order"),state.getDesired().reportIds());
        assertTrue(state.getPendingIntent().forbids(SemanticIntent.Action.PREPARE_DISPATCH));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE conversation_id=?",Integer.class,id));
        var prepared=turn(id,"剩下的帮我派单吧");
        assertTrue(prepared.stream().anyMatch(e->"plan".equals(e.event())),text(prepared));
        var plan=plans.getOwned(user(),store.read(user(),id).getPlanId());
        assertEquals("PENDING",plan.plan().getStatus());
        assertTrue(plan.items().stream().allMatch(i->i.getAttemptCount()==null || i.getAttemptCount()==0));
        var changed=turn(id,"只查费用报表，再加销售报表，最后去掉费用报表");
        assertTrue(changed.stream().anyMatch(e->"preview".equals(e.event())),text(changed));
        assertEquals(List.of("rpt-sales-order"),store.read(user(),id).getDesired().reportIds());
    }

    @Test void orderedRecordOperationsUseOnePreviewAndCommitNoPartialSelection() {
        String id=conversation();turn(id,"A公司销售报表的");
        String preview=store.read(user(),id).getPreviewId();
        turn(id,"排除SO2026002");
        assertEquals(1,store.read(user(),id).getExcludedRecords().size());
        turn(id,"排除SO2026002，再恢复SO2026002");
        var state=store.read(user(),id);assertEquals(preview,state.getPreviewId());assertTrue(state.getExcludedRecords().isEmpty());
        var invalid=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(
                new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,SemanticIntent.Operation.ADD,List.of("SO2026002"),"排除SO2026002"),
                new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,SemanticIntent.Operation.ADD,List.of("UNKNOWN99"),"排除UNKNOWN99")),List.of(),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(invalid).when(parser).parse(org.mockito.ArgumentMatchers.eq("排除SO2026002，再排除UNKNOWN99"),org.mockito.ArgumentMatchers.any());
        var failed=turn(id,"排除SO2026002，再排除UNKNOWN99");
        assertFalse(failed.stream().anyMatch(e->"plan".equals(e.event())));
        assertTrue(store.read(user(),id).getExcludedRecords().isEmpty());
        assertTrue(store.read(user(),id).isUnresolvedRequest());
        assertFalse(store.read(user(),id).isUnresolvedRecords(),"拒绝草稿不覆盖原有已成功选择");
        unresolvedContinuation("剩下的帮我派单吧");
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
    }

    @Test void malformedAndConflictingModelProgramsCannotMutateScopeOrCreatePlans() {
        String id=conversation();turn(id,"A公司销售报表的");
        var before=store.read(user(),id).getDesired();
        var conflict=new SemanticIntent(1,SemanticIntent.Action.PREPARE_DISPATCH,List.of(
                new SemanticIntent.ScopeChange(SemanticIntent.Target.REPORTS,SemanticIntent.Operation.REPLACE,List.of("费用报表"),"费用报表")),
                List.of(new SemanticIntent.Restriction(SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.RestrictionScope.THIS_TURN,"不要派单")),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(conflict).when(parser).parse(org.mockito.ArgumentMatchers.eq("费用报表不要派单"),org.mockito.ArgumentMatchers.any());
        var events=turn(id,"费用报表不要派单");
        assertFalse(events.stream().anyMatch(e->Set.of("plan","preview").contains(e.event())));
        assertEquals(before,store.read(user(),id).getDesired());
        assertTrue(store.read(user(),id).isUnresolvedRequest());
        unresolvedContinuation("剩下的帮我派单吧");
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE conversation_id=?",Integer.class,id));
    }
    @Test void configuredAmountThenDocumentExclusionKeepsScopeAndFrozenFactsAcrossReload() {
        String id=conversation();turn(id,"查一下我有哪些可以派单");var before=store.read(user(),id);String preview=before.getPreviewId();
        String message="销售报表金额大于96000的不要";
        var condition=new SemanticIntent.FieldCondition("amount",SemanticIntent.Comparison.GT,List.of("96000"),"金额大于96000");
        var intent=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,SemanticIntent.Operation.EXCLUDE,List.of(),message,List.of("销售报表"),SemanticIntent.SelectorKind.FIELDS,SemanticIntent.Quantifier.ALL,List.of(new SemanticIntent.ConditionGroup(List.of(condition))))),List.of(),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(intent).when(parser).parse(org.mockito.ArgumentMatchers.eq(message),org.mockito.ArgumentMatchers.any());
        var events=turn(id,message);assertFalse(events.stream().anyMatch(e->"preview".equals(e.event())),text(events));
        var after=store.read(user(),id);assertEquals(before.getDesired(),after.getDesired());assertEquals(1,after.getExcludedRecords().size());
        String next="销售报表SO2026002不要";
        var doc=new SemanticIntent(1,SemanticIntent.Action.PREVIEW,List.of(new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,SemanticIntent.Operation.EXCLUDE,List.of("SO2026002"),next,List.of("销售报表"),SemanticIntent.SelectorKind.DOCUMENT,SemanticIntent.Quantifier.ONE)),List.of(),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(doc).when(parser).parse(org.mockito.ArgumentMatchers.eq(next),org.mockito.ArgumentMatchers.any());turn(id,next);
        var restored=new DialogueStore(jdbc,tx,props).read(user(),id);assertEquals(preview,restored.getPreviewId());assertEquals(2,restored.getExcludedRecords().size());
        var frozen=previews.getOwned(user(),preview).candidates().stream().filter(r->"SO2026001".equals(r.docNo())).findFirst().orElseThrow();
        assertEquals("128000.00",frozen.fields().stream().filter(f->f.name().equals("amount")).findFirst().orElseThrow().value());
        assertTrue(text(turn(id,"剩下的帮我派单吧")).contains("待确认"));
        assertTrue(plans.getOwned(user(),store.read(user(),id).getPlanId()).candidates().stream().allMatch(r->!r.fields().isEmpty()));
    }
    @Test void unsupportedFieldRequestAllowsNewDocumentCorrectionWithoutCompanyPollution() {
        String id=conversation();turn(id,"查一下我有哪些可以派单");var before=store.read(user(),id);
        String bad="按未配置的信用评分排除";
        org.mockito.Mockito.doReturn(new SemanticIntent(1,SemanticIntent.Action.CLARIFY,List.of(),List.of(),List.of(),List.of(bad),SemanticIntent.Clarify.ACTION)).when(parser).parse(org.mockito.ArgumentMatchers.eq(bad),org.mockito.ArgumentMatchers.any());
        turn(id,bad);var failed=store.read(user(),id);assertEquals(before.getDesired(),failed.getDesired());assertFalse(failed.isUnresolvedCompany());assertFalse(failed.isUnresolvedReports());
        unresolvedContinuation("剩下的帮我派单吧");
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
        var corrected=turn(id,"排除SO2026002");assertTrue(text(corrected).contains("已排除 1 条"),text(corrected));assertFalse(store.read(user(),id).isUnresolvedRequest());
    }
}
