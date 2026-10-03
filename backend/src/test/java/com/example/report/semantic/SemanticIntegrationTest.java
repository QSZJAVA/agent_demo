package com.example.report.semantic;

import com.example.report.agent.AgentChatService;
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

/** Actual application routing, real MySQL CAS, real preview/plan services, no real gateway/model. */
@EnabledIfEnvironmentVariable(named="P2_IT",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"demo.reset-on-startup=true","agent.semantic.mode=active","agent.retention-sweep-ms=3600000","spring.data.redis.database=15"})
@ActiveProfiles("mock") @DirtiesContext
class SemanticIntegrationTest {
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
    static JdbcTemplate cleanup;
    @BeforeEach void cleanupHandle(){cleanup=jdbc;rateTenant=SCHEMA+"_"+UUID.randomUUID().toString().substring(0,8);}
    @AfterAll static void drop(){if(cleanup!=null && SCHEMA.matches("semantic_it_[a-f0-9]{32}")){
        assertEquals(SCHEMA,cleanup.queryForObject("SELECT DATABASE()",String.class));cleanup.execute("DROP DATABASE `"+SCHEMA+"`");}}
    CurrentUser user(){return permissions.resolve("user1");}
    String conversation(){return conversations.create(user(),"test").getId();}
    List<ServerSentEvent<Object>> turn(String id,String message){return chat.chat(user(),id,message,List.of(),null,List.of()).collectList().block(Duration.ofSeconds(30));}
    String text(List<ServerSentEvent<Object>> events){return events.stream().filter(e->"text".equals(e.event())||"error".equals(e.event())).map(e->JsonUtil.toJson(e.data())).reduce("",String::concat);}
    @Test void reportedConversationReplaysWithoutInventedPermissionOrScope() {
        String id=conversation();
        var a=turn(id,"查一下A公司有哪些可以派单");
        assertTrue(a.stream().anyMatch(e->"preview".equals(e.event())),text(a));
        String first=store.read(user(),id).getPreviewId();
        var b=turn(id,"查一下B公司有哪些可以派单");
        assertTrue(text(b).contains("无权查看 B 公司"),text(b));
        assertFalse(b.stream().anyMatch(e->"preview".equals(e.event())));
        var rejected=store.read(user(),id);
        assertEquals("B",rejected.getDesired().companyCode());assertEquals("A",rejected.getEffective().companyCode());
        var inherited=turn(id,"现在我只想派销售报表的");
        assertTrue(text(inherited).contains("无权查看 B 公司"),text(inherited));
        assertFalse(inherited.stream().anyMatch(e->"plan".equals(e.event())));
        assertEquals(first,store.read(user(),id).getPreviewId());
        var corrected=turn(id,"A公司销售报表的");
        assertTrue(corrected.stream().anyMatch(e->"preview".equals(e.event())),text(corrected));
        var state=store.read(user(),id);assertEquals("A",state.getDesired().companyCode());
        assertEquals(List.of("rpt-sales-order"),state.getDesired().reportIds());
        assertTrue(previews.getOwned(user(),state.getPreviewId()).candidates().stream().allMatch(r->"A".equals(r.companyCode())));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM semantic_turn WHERE conversation_id=?",Integer.class,id));
    }
    @Test void selectionSurvivesReloadAndPreparationNeverExecutesEvenIfLegacyConfirmationDisabled() {
        String id=conversation();turn(id,"A公司销售报表的");
        String preview=store.read(user(),id).getPreviewId();
        var selection=turn(id,"排除SO2026002");
        assertFalse(selection.stream().anyMatch(e->"preview".equals(e.event())),text(selection));
        var restored=new DialogueStore(jdbc,tx,props).read(user(),id);
        assertEquals(preview,restored.getPreviewId());assertEquals(1,restored.getExcludedRecords().size());
        boolean before=props.getDispatch().isRequireConfirm();props.getDispatch().setRequireConfirm(false);
        try {
            var events=turn(id,"剩下的帮我派单吧");
            assertTrue(events.stream().anyMatch(e->"plan".equals(e.event())),text(events));
            var state=store.read(user(),id);var plan=plans.getOwned(user(),state.getPlanId());
            assertEquals("PENDING",plan.plan().getStatus());
            assertTrue(plan.candidates().stream().noneMatch(c->"SO2026002".equals(c.docNo())));
            assertTrue(plan.items().stream().allMatch(i->i.getAttemptCount()==null || i.getAttemptCount()==0));
            assertTrue(text(turn(id,"查看派单结果")).contains("待确认"));
            assertTrue(text(turn(id,"取消清单")).contains("已取消"));
        } finally {props.getDispatch().setRequireConfirm(before);}
    }
    @Test void expiredPreviewRetainsExclusionsWithAndWithoutUiSelection() {
        for (boolean ui : List.of(false,true)) {
            String id=conversation();turn(id,"A公司销售报表的");turn(id,"排除SO2026002");
            var before=store.read(user(),id);
            jdbc.update("UPDATE dispatch_preview SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",before.getPreviewId());
            var events=chat.chat(user(),id,"剩下的帮我派单吧",List.of(),ui?before.getPreviewId():null,
                    ui?before.getExcludedRecords():List.of()).collectList().block(Duration.ofSeconds(30));
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
        org.mockito.Mockito.doReturn(new SemanticIntent(2,SemanticIntent.Action.PREVIEW,List.of(
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
            var events=chat.chat(user(),id,"A公司销售报表的",List.of(),source,List.of()).collectList().block(Duration.ofSeconds(30));
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
    @Test void unknownMockInputClarifiesAndDoesNotExecute() {
        String id=conversation();var events=turn(id,"完全未收录的模拟输入");
        assertFalse(events.stream().anyMatch(e->Set.of("preview","plan","result").contains(e.event())));
        assertEquals(DialogueState.Phase.CLARIFY,store.read(user(),id).getPhase());
    }
    @Test void parserFailureCannotBeFollowedByDispatchOfOldScope() {
        String id=conversation();turn(id,"A公司销售报表的");
        org.mockito.Mockito.doThrow(new ApiException(422,"协议无效"))
                .when(parser).parse(org.mockito.ArgumentMatchers.eq("解析故障"),org.mockito.ArgumentMatchers.any());
        turn(id,"解析故障");
        var events=turn(id,"剩下的帮我派单吧");
        assertFalse(events.stream().anyMatch(e->"plan".equals(e.event())),text(events));
        assertTrue(store.read(user(),id).isUnresolvedCompany());
        var correction=turn(id,"A公司销售报表的");
        assertTrue(correction.stream().anyMatch(e->"preview".equals(e.event())),text(correction));
    }
    @Test void expiredWorkerCannotActivatePreviewAndEraseCleansTurnEvidence() {
        String id=conversation();turn(id,"A公司销售报表的");
        String first=store.read(user(),id).getPreviewId();
        try(var session=store.acquire(user(),id)) {
            jdbc.update("UPDATE semantic_dialogue SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE conversation_id=?",id);
            assertThrows(ApiException.class,()->previews.preview(user(),id,
                    new PreviewCommand("PREVIEW","semantic",null,List.of("rpt-sales-order"),new PreviewCommand.Filters("A"),List.of(),"replace"),
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
        assertTrue(store.read(user(),id).isUnresolvedCompany());
        org.mockito.Mockito.doCallRealMethod().when(parser).parse(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
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
        var invalid=new SemanticIntent(2,SemanticIntent.Action.PREVIEW,List.of(
                new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,SemanticIntent.Operation.ADD,List.of("SO2026002"),"排除SO2026002"),
                new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,SemanticIntent.Operation.ADD,List.of("UNKNOWN99"),"排除UNKNOWN99")),List.of(),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(invalid).when(parser).parse(org.mockito.ArgumentMatchers.eq("排除SO2026002，再排除UNKNOWN99"),org.mockito.ArgumentMatchers.any());
        var failed=turn(id,"排除SO2026002，再排除UNKNOWN99");
        assertFalse(failed.stream().anyMatch(e->"plan".equals(e.event())));
        assertTrue(store.read(user(),id).getExcludedRecords().isEmpty());
        assertTrue(store.read(user(),id).isUnresolvedRecords());
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
    }

    @Test void malformedAndConflictingModelProgramsCannotMutateScopeOrCreatePlans() {
        String id=conversation();turn(id,"A公司销售报表的");
        var before=store.read(user(),id).getDesired();
        var conflict=new SemanticIntent(2,SemanticIntent.Action.PREPARE_DISPATCH,List.of(
                new SemanticIntent.ScopeChange(SemanticIntent.Target.REPORTS,SemanticIntent.Operation.REPLACE,List.of("费用报表"),"费用报表")),
                List.of(new SemanticIntent.Restriction(SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.RestrictionScope.THIS_TURN,"不要派单")),SemanticIntent.Clarify.NONE);
        org.mockito.Mockito.doReturn(conflict).when(parser).parse(org.mockito.ArgumentMatchers.eq("费用报表不要派单"),org.mockito.ArgumentMatchers.any());
        var events=turn(id,"费用报表不要派单");
        assertFalse(events.stream().anyMatch(e->Set.of("plan","preview").contains(e.event())));
        assertEquals(before,store.read(user(),id).getDesired());
        assertTrue(store.read(user(),id).isUnresolvedReports());
        assertFalse(turn(id,"剩下的帮我派单吧").stream().anyMatch(e->"plan".equals(e.event())));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE conversation_id=?",Integer.class,id));
    }
}
