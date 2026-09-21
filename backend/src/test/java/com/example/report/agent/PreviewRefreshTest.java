package com.example.report.agent;

import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.DispatchService;
import com.example.report.dispatch.PlanStore;
import com.example.report.dispatch.PreviewStore;
import com.example.report.entity.AgentConversation;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.report.ReportType;
import com.example.report.rule.Candidate;
import com.example.report.rule.DispatchCandidateService;
import com.example.report.rule.RuleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实工具与快照存储逻辑、内存 Redis 替身，不访问演示库或真实模型。 */
class PreviewRefreshTest {

    private final CurrentUser user = new CurrentUser("user1", "用户1", Set.of("A"), false);
    private final Candidate sale = candidate("sales", "SO2026001");
    private final Candidate expense = candidate("expense", "EXP-2026-0001");
    private PreviewStore previews;
    private PlanStore plans;
    private DispatchTools tools;
    private AgentChatService chat;
    private ChatClient client;
    private ChatMemory memory;
    private ConversationService conversations;
    private DispatchCandidateService candidates;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Map<String, String> storage = new HashMap<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> storage.get(call.getArgument(0)));
        doAnswer(call -> {
            storage.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> storage.remove(call.getArgument(0)) != null);
        AgentProperties props = new AgentProperties();
        previews = new PreviewStore(redis, props);
        plans = new PlanStore(redis, props);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.resolve("user1")).thenReturn(user);
        candidates = mock(DispatchCandidateService.class);
        when(candidates.findCandidates(user.companies(), null)).thenReturn(List.of(sale, expense));
        when(candidates.findCandidates(user.companies(), ReportType.SALES)).thenReturn(List.of(sale));
        when(candidates.findCandidates(user.companies(), ReportType.RECEIVABLE)).thenReturn(List.of());
        when(candidates.findCandidates(user.companies(), ReportType.EXPENSE)).thenReturn(List.of(expense));
        RuleCache rules = mock(RuleCache.class);
        when(rules.fingerprint()).thenReturn("rules-v1");
        conversations = mock(ConversationService.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        when(conversations.getOwned(user, "conversation-1")).thenReturn(conversation);
        // 方案 C 下所有消息都会先交给模型；这里让模型返回空文本，由服务端兜底补预览
        client = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(client.prompt().user(anyString()).toolContext(any()).advisors(any(java.util.function.Consumer.class))
                .stream().content()).thenReturn(Flux.just(""));
        memory = mock(ChatMemory.class);
        tools = new DispatchTools(permissions, candidates, rules, previews, plans,
                mock(DispatchService.class), conversations, props);
        chat = new AgentChatService(client, conversations, mock(ChatModel.class), tools, memory);
    }

    @Test
    void scopeCorrectionCreatesCardAndInvalidatesOldPreviewAndPlan() {
        PreviewPayload old = preview(chat("全部报表"));
        Map<String, Object> pending = map(tools.dispatch(old.previewId(), null, context("conversation-1")));
        String planId = (String) pending.get("planId");
        assertTrue(plans.load(user.userId(), planId).isPresent());

        List<ServerSentEvent<Object>> events = chat("我说销售报表");
        PreviewPayload fresh = preview(events);
        assertNotEquals(old.previewId(), fresh.previewId());
        assertEquals(List.of(sale), fresh.records());
        // 本轮先交给模型（这里返回空文本），模型没调工具，服务端在文本流结束后兜底补发了预览卡片
        assertEquals(List.of("conversation", "preview", "done"), events.stream().map(ServerSentEvent::event).toList());
        assertTrue(previews.load(user.userId(), old.previewId()).isEmpty());
        assertTrue(plans.load(user.userId(), planId).isEmpty());
        assertEquals("error", map(tools.dispatch(old.previewId(), null, context("conversation-1"))).get("status"));
        assertEquals(fresh.previewId(), previews.loadLatest(user.userId(), "conversation-1").orElseThrow().id());
        assertEquals(1, map(tools.dispatch(null, null, context("conversation-1"))).get("count"));
    }

    @Test
    void zeroResultsStillReplaceOldCardAndPlan() {
        PreviewPayload old = preview(chat("全部报表"));
        String planId = (String) map(tools.dispatch(null, null, context("conversation-1"))).get("planId");
        PreviewPayload fresh = preview(chat("只看应收报表"));
        assertEquals(0, fresh.total());
        assertTrue(fresh.records().isEmpty());
        assertTrue(previews.load(user.userId(), old.previewId()).isEmpty());
        assertTrue(plans.load(user.userId(), planId).isEmpty());
        assertEquals("error", map(tools.dispatch(null, null, context("conversation-1"))).get("status"));
    }

    @Test
    void appendScopeMergesWithPreviousPreviewScope() {
        // "应收报表" → 范围 [receivable]
        assertTrue(preview(chat("应收报表")).records().isEmpty());
        assertEquals(List.of("receivable"),
                previews.loadLatest(user.userId(), "conversation-1").orElseThrow().reportTypes());

        // "加上销售报表" → 与上一轮范围合并为 sales + receivable，而不是只查销售报表
        PreviewPayload fresh = preview(chat("加上销售报表"));
        assertEquals(List.of(sale), fresh.records());
        assertEquals(List.of("sales", "receivable"),
                previews.loadLatest(user.userId(), "conversation-1").orElseThrow().reportTypes());
    }

    @Test
    void followUpReportQuestionMergesWithPreviousPreviewScope() {
        // 上一轮只看应收（0 条），追问"费用报表呢"应当合并成 应收 + 费用
        chat("应收报表");
        PreviewPayload fresh = preview(chat("费用报表呢"));
        assertEquals(List.of(expense), fresh.records());
        assertEquals(1, fresh.total());
        assertEquals(List.of("receivable", "expense"),
                previews.loadLatest(user.userId(), "conversation-1").orElseThrow().reportTypes());
    }

    @Test
    void explicitScopeSwitchStillReplacesPreviousScope() {
        chat("应收报表");
        PreviewPayload fresh = preview(chat("只看费用报表呢"));
        assertEquals(List.of(expense), fresh.records());
        assertEquals(List.of("expense"),
                previews.loadLatest(user.userId(), "conversation-1").orElseThrow().reportTypes());
    }

    @Test
    void multipleReportTypesInOneRequest() {
        PreviewPayload fresh = preview(chat("销售报表和应收报表有哪些可以派单"));
        assertEquals(List.of(sale), fresh.records());
        assertEquals(1, fresh.total());
        assertEquals(List.of("sales", "receivable"),
                previews.loadLatest(user.userId(), "conversation-1").orElseThrow().reportTypes());
    }

    @Test
    void removeScopeSubtractsFromPreviousPreviewScope() {
        // "销售报表和应收报表" → 范围 [sales, receivable]
        preview(chat("销售报表和应收报表有哪些可以派单"));
        assertEquals(List.of("sales", "receivable"),
                previews.loadLatest(user.userId(), "conversation-1").orElseThrow().reportTypes());

        // "应收的也删掉" → 从上一轮范围中减去应收，只剩销售报表
        PreviewPayload fresh = preview(chat("应收的也删掉"));
        assertEquals(List.of(sale), fresh.records());
        assertEquals(List.of("sales"),
                previews.loadLatest(user.userId(), "conversation-1").orElseThrow().reportTypes());
    }

    @Test
    void removeScopeWithoutPreviousPreviewSubtractsFromAllReports() {
        // 新会话直接说"不要应收报表和销售报表的" → 全部报表减去这两张 = 费用报表，
        // 不能退化成"只查应收 + 销售报表"
        List<ServerSentEvent<Object>> events = chat("不要应收报表和销售报表的");
        assertEquals(List.of("expense"),
                previews.loadLatest(user.userId(), "conversation-1").orElseThrow().reportTypes());
        assertEquals(List.of(expense), preview(events).records());
    }

    @Test
    void previewExcludesRecordsByDocNoOrLabelKeyword() {
        AgentEventChannel channel = new AgentEventChannel();
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(AgentEventChannel.CONTEXT_KEY, channel);
        ToolContext ctx = new ToolContext(data);

        // 按单据号排除销售 → 只剩费用
        assertEquals(1, map(tools.previewDispatchable("all", null, List.of(sale.docNo()), ctx)).get("total"));
        // 按摘要关键词排除（用户常只说"云服务"这类描述）→ 两条摘要都是"测试记录"，全部排除
        assertEquals(0, map(tools.previewDispatchable("all", null, List.of("测试记录"), ctx)).get("total"));
    }

    @Test
    void recognizesPreviewClaimsThatNeedALocalCorrection() {
        // 模型没调工具却声称预览已刷新 → 需要追加纠正提示
        assertTrue(AgentChatService.claimsPreviewRefreshed("预览已重新生成，含应收 + 费用："));
        assertTrue(AgentChatService.claimsPreviewRefreshed("已经按新的范围重新查询。\n预览已刷新。"));
        // 服务端强制预览路径的文案、以及否认句都不能误判
        assertFalse(AgentChatService.claimsPreviewRefreshed(
                "已重新查询应收报表，共 2 条可派单记录。最新预览见卡片。"));
        assertFalse(AgentChatService.claimsPreviewRefreshed("本轮没有生成新的预览，上方卡片仍是上一次的结果。"));
    }

    @Test
    void recognizesRecheckClaimsThatNeedALocalCorrection() {
        // 线上实际漏检的场景："已重查…"与"预览编号…"被写成两句，按句判断会漏掉
        assertTrue(AgentChatService.claimsRechecked(
                "已重查应收报表：0 条可派单记录。预览编号 63c4f869bd97fa63adc47590d5ad3ae9。"));
        assertTrue(AgentChatService.claimsRechecked("已重新查询应收报表，共 4 条。"));
        // 建议句、否认句都不能误判
        assertFalse(AgentChatService.claimsRechecked("我建议重新查询一次应收报表。"));
        assertFalse(AgentChatService.claimsRechecked("需要重新查询应收报表后再派单。"));
        assertFalse(AgentChatService.claimsRechecked("本轮没有生成新的预览。"));
    }

    @Test
    void hallucinatedPreviewIdNeverReachesTheUser() {
        // 模型没调工具还编了个编号，服务端识别出范围后兜底补了真实预览
        stubReply("已重查应收报表：0 条可派单记录。预览编号 `63c4f869bd97fa63adc47590d5ad3ae9`。");
        List<ServerSentEvent<Object>> events = chat("应收报表再查下");
        String text = text(events);
        assertFalse(text.contains("63c4f869bd97fa63adc47590d5ad3ae9"), "编造的编号不能展示给用户");
        assertTrue(text.contains("（见下方卡片）"), "编号被替换成了指路文案");
        assertEquals(0, preview(events).total());
        // 卡片是服务端兜底补出来的，模型文本里的条数依然不可信，必须给出提示
        assertTrue(text.contains("不是本轮查询得到的"));
        assertFalse(text.contains("本轮没有生成新的预览"), "卡片已经被兜底刷新，就不能再说卡片还是旧的");
    }

    @Test
    void modelClaimingZeroWhileFallbackFindsRecordsIsCorrected() {
        // 线上场景：模型说"当前 0 条"，服务端兜底却查出了记录，卡片与文本自相矛盾
        when(candidates.findCandidates(user.companies(), ReportType.RECEIVABLE))
                .thenReturn(List.of(candidate("receivable", "INV-2026-0007")));
        stubReply("应收报表重查结果：当前 0 条可派单记录。上次预览的 4 条现在已经查不到了。");
        List<ServerSentEvent<Object>> events = chat("应收报表再查下");
        String text = text(events);
        assertTrue(text.contains("0 条"), "模型原文要保留，不能改写");
        assertEquals(1, preview(events).total(), "卡片是服务端兜底查出来的真实结果");
        assertTrue(text.contains("不是本轮查询得到的"), "必须指出条数未经本轮查询");
    }

    @Test
    void fabricatedPreviewIdGetsALocalCorrectionWhenNothingWasProduced() {
        // 句子不匹配任何报表范围 → 服务端不会兜底，本轮一张卡都没刷新
        stubReply("已重查应收报表：0 条可派单记录。预览编号 `63c4f869bd97fa63adc47590d5ad3ae9`。两张报表现在都是空的。");
        String text = text(chat("你好"));
        assertFalse(text.contains("63c4f869bd97fa63adc47590d5ad3ae9"));
        assertTrue(text.contains("不是本轮生成的预览"), "必须明确告诉用户这个编号不可信");
        assertTrue(text.contains("本轮没有生成新的预览"), "必须告诉用户卡片仍是上一次的结果");
    }

    private void stubReply(String reply) {
        when(client.prompt().user(anyString()).toolContext(any()).advisors(any(java.util.function.Consumer.class))
                .stream().content()).thenReturn(Flux.just(reply));
    }

    private static String text(List<ServerSentEvent<Object>> events) {
        return events.stream().filter(e -> "text".equals(e.event()))
                .map(e -> String.valueOf(((Map<?, ?>) e.data()).get("delta")))
                .collect(java.util.stream.Collectors.joining());
    }

    @Test
    void refreshDoesNotInvalidateAnotherConversation() {
        String otherPreview = (String) map(tools.previewDispatchable("all", context("conversation-2"))).get("previewId");
        String otherPlan = (String) map(tools.dispatch(null, null, context("conversation-2"))).get("planId");
        chat("我说销售报表");
        assertTrue(previews.load(user.userId(), otherPreview).isPresent());
        assertTrue(plans.load(user.userId(), otherPlan).isPresent());
        assertEquals("error", map(tools.dispatch(otherPreview, null, context("conversation-1"))).get("status"));
    }

    @Test
    void oldCheckboxExclusionsDoNotPollutePreviewCreatedInSameTurn() {
        AgentEventChannel channel = new AgentEventChannel();
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(AgentEventChannel.CONTEXT_KEY, channel);
        data.put(ToolContextKeys.UI_EXCLUDES, List.of(expense.docNo()));
        ToolContext ctx = new ToolContext(data);
        tools.previewDispatchable("sales", ctx);
        Map<String, Object> pending = map(tools.dispatch(null, null, ctx));
        assertEquals("pending_confirm", pending.get("status"));
        assertEquals(1, pending.get("count"));
        assertEquals(List.of(), pending.get("excluded"));
    }

    @Test
    void currentCheckboxExclusionsAreStillApplied() {
        chat("全部报表");
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(ToolContextKeys.UI_EXCLUDES, List.of(expense.docNo()));
        Map<String, Object> pending = map(tools.dispatch(null, null, new ToolContext(data)));
        assertEquals(1, pending.get("count"));
        assertEquals(List.of(expense.docNo()), pending.get("excluded"));
    }

    @Test
    void unauthorizedExplicitCompanyDoesNotFallBackToDefaultCompanies() {
        Map<String, Object> result = map(tools.previewDispatchable("all", "B", null, context("conversation-1")));
        assertEquals("error", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("无权查看 B 公司"));
        verify(candidates, never()).findCandidates(anySet(), any());
    }

    private List<ServerSentEvent<Object>> chat(String message) {
        return chat.chat(user, "conversation-1", message, List.of()).collectList().block(Duration.ofSeconds(5));
    }

    private static PreviewPayload preview(List<ServerSentEvent<Object>> events) {
        assertEquals(1, events.stream().filter(e -> "preview".equals(e.event())).count());
        return events.stream().filter(e -> "preview".equals(e.event())).map(e -> (PreviewPayload) e.data()).findFirst().orElseThrow();
    }

    private static ToolContext context(String conversationId) {
        return new ToolContext(Map.of(ToolContextKeys.USER_ID, "user1", ToolContextKeys.CONVERSATION_ID, conversationId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object result) {
        return (Map<String, Object>) result;
    }

    private static Candidate candidate(String type, String docNo) {
        return new Candidate(type, ReportType.fromCode(type).label(), 1L, docNo, "A", "测试记录",
                BigDecimal.valueOf(2000), LocalDate.of(2026, 1, 1), "测试规则", 1, "金额 > 20 元");
    }
}
