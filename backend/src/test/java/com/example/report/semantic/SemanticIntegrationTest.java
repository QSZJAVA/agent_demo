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
}
