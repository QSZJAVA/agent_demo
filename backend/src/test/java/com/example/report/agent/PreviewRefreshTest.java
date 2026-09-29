package com.example.report.agent;

import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.DispatchService;
import com.example.report.dispatch.DispatchVersionService;
import com.example.report.entity.AgentConversation;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPreview;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.rule.Candidate;
import com.example.report.support.DispatchHarness;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.EXPENSE;
import static com.example.report.support.TestCatalog.RECEIVABLE;
import static com.example.report.support.TestCatalog.SALES;
import static com.example.report.support.TestCatalog.USER1;
import static com.example.report.support.TestCatalog.USER3;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对话链路场景测试：真实的工具、服务端兜底、报表解析、预览 / 清单状态机（内存存储），不访问数据库或真实模型。
 */
class PreviewRefreshTest {

    private final Candidate sale = candidate(SALES, "1", "SO2026001", "A", "测试记录");
    private final Candidate expense = candidate(EXPENSE, "1", "EXP-2026-0001", "A", "测试记录");
    private DispatchHarness h;
    private DispatchTools tools;
    private AgentChatService chat;
    private ChatClient client;
    private ConversationService conversations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        h = new DispatchHarness().put(SALES, sale).put(EXPENSE, expense);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.resolve("user1")).thenReturn(USER1);
        when(permissions.resolve("user3")).thenReturn(USER3);
        conversations = mock(ConversationService.class);
        for (String id : List.of("conversation-1", "conversation-2")) {
            AgentConversation conversation = new AgentConversation();
            conversation.setId(id);
            when(conversations.getOwned(any(CurrentUser.class), eq(id))).thenReturn(conversation);
        }
        // 未指定公司的消息先交给模型；这里默认返回空文本，由服务端兜底补预览。
        // 模型回复统一由 reply 生成，它能拿到本轮真实的 ToolContext，用来模拟"模型在流式输出中调用了工具"
        client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        Map<String, Object> captured = new HashMap<>();
        when(client.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.toolContext(any())).thenAnswer(inv -> {
            captured.clear();
            captured.putAll(inv.getArgument(0));
            return spec;
        });
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.content()).thenAnswer(inv -> reply.apply(new ToolContext(new HashMap<>(captured))));
        stubReply("");
        tools = new DispatchTools(permissions, h.previews, h.plans, mock(DispatchService.class), h.catalogService,
                conversations, h.props);
        chat = new AgentChatService(client, conversations, mock(ChatModel.class), tools, h.catalogService);
    }

    @Test
    void scopeCorrectionCreatesCardAndInvalidatesOldPreviewAndPlan() {
        PreviewPayload old = preview(chat("全部报表"));
        Map<String, Object> pending = map(tools.dispatch(old.previewId(), null, context("conversation-1")));
        String planId = (String) pending.get("planId");
        assertEquals(DispatchPlan.PENDING, planStatus(planId));

        List<ServerSentEvent<Object>> events = chat("我说销售报表");
        PreviewPayload fresh = preview(events);
        assertNotEquals(old.previewId(), fresh.previewId());
        assertEquals(List.of(sale), fresh.records());
        // 本轮先交给模型（这里返回空文本），模型没调工具，服务端在文本流结束后兜底补发了预览卡片
        assertEquals(List.of("conversation", "preview", "done"), events.stream().map(ServerSentEvent::event).toList());
        // 旧预览、旧清单由服务端置为失效，前端刷新后看到的也是这个状态
        assertEquals(DispatchPreview.SUPERSEDED, previewStatus(old.previewId()));
        assertEquals(DispatchPlan.EXPIRED, planStatus(planId));
        assertEquals("error", map(tools.dispatch(old.previewId(), null, context("conversation-1"))).get("status"));
        assertEquals(1, map(tools.dispatch(null, null, context("conversation-1"))).get("count"));
    }

    @Test
    void zeroResultsStillReplaceOldCardAndPlan() {
        PreviewPayload old = preview(chat("全部报表"));
        String planId = (String) map(tools.dispatch(null, null, context("conversation-1"))).get("planId");
        PreviewPayload fresh = preview(chat("只看应收报表"));
        assertEquals(0, fresh.total());
        assertTrue(fresh.records().isEmpty());
        assertEquals(List.of("应收报表"), fresh.byReport().stream().map(PreviewPayload.ReportCount::reportName).toList(),
                "0 条也要明确列出查询的报表");
        assertEquals(DispatchPreview.SUPERSEDED, previewStatus(old.previewId()));
        assertEquals(DispatchPlan.EXPIRED, planStatus(planId));
        assertEquals("error", map(tools.dispatch(null, null, context("conversation-1"))).get("status"));
    }

    @Test
    void appendScopeMergesWithPreviousPreviewScope() {
        assertTrue(preview(chat("应收报表")).records().isEmpty());
        assertEquals(List.of(RECEIVABLE), latestScope());
        // "加上销售报表" → 与上一轮范围合并为 销售 + 应收，而不是只查销售报表
        PreviewPayload fresh = preview(chat("加上销售报表"));
        assertEquals(List.of(sale), fresh.records());
        assertEquals(List.of(SALES, RECEIVABLE), latestScope());
    }

    @Test
    void followUpReportQuestionMergesWithPreviousPreviewScope() {
        chat("应收报表");
        PreviewPayload fresh = preview(chat("费用报表呢"));
        assertEquals(List.of(expense), fresh.records());
        assertEquals(List.of(RECEIVABLE, EXPENSE), latestScope());
    }

    @Test
    void explicitScopeSwitchStillReplacesPreviousScope() {
        chat("应收报表");
        PreviewPayload fresh = preview(chat("只看费用报表呢"));
        assertEquals(List.of(expense), fresh.records());
        assertEquals(List.of(EXPENSE), latestScope());
    }

    @Test
    void multipleReportsInOneRequest() {
        PreviewPayload fresh = preview(chat("销售报表和应收报表有哪些可以派单"));
        assertEquals(List.of(sale), fresh.records());
        assertEquals(List.of(SALES, RECEIVABLE), latestScope());
    }

    @Test
    void aliasesResolveToTheSameReport() {
        // 简称、历史名称、英文名都指向同一个 report_id（T-RESOLVE-02）
        for (String query : List.of("销售台账", "订单销售表", "sales report")) {
            Map<String, Object> result = map(tools.previewDispatchable(query, null, null, null, context("conversation-1")));
            assertEquals("ok", result.get("status"), query);
            assertEquals(List.of(SALES), latestScope(), query);
        }
    }

    @Test
    void removeScopeSubtractsFromPreviousPreviewScope() {
        preview(chat("销售报表和应收报表有哪些可以派单"));
        PreviewPayload fresh = preview(chat("应收的也删掉"));
        assertEquals(List.of(sale), fresh.records());
        assertEquals(List.of(SALES), latestScope());
    }

    @Test
    void removeScopeWithoutPreviousPreviewSubtractsFromAllReports() {
        // 新会话直接说"不要应收报表和销售报表的" → 全部报表减去这两张 = 费用报表，不能退化成"只查应收 + 销售报表"
        List<ServerSentEvent<Object>> events = chat("不要应收报表和销售报表的");
        assertEquals(List.of(EXPENSE), latestScope());
        assertEquals(List.of(expense), preview(events).records());
    }

    @Test
    void previewExcludesRecordsByDocNoOrLabelKeyword() {
        assertEquals(1, map(tools.previewDispatchable(null, null, List.of(sale.docNo()), null, context("conversation-1"))).get("total"));
        // 按摘要关键词排除（用户常只说"云服务"这类描述）→ 两条摘要都是"测试记录"，全部排除
        assertEquals(0, map(tools.previewDispatchable(null, null, List.of("测试记录"), null, context("conversation-1"))).get("total"));
    }

    @Test
    void ambiguousReportAsksTheUserToChooseInsteadOfGuessing() {
        // “客户对账”同时是销售报表和应收报表的别名（T-RESOLVE-03）
        List<ServerSentEvent<Object>> events = chat("查客户对账有哪些可以派单");
        assertEquals(List.of("conversation", "choice", "done"), events.stream().map(ServerSentEvent::event).toList());
        ReportChoicePayload choice = (ReportChoicePayload) events.get(1).data();
        assertEquals(List.of("销售报表", "应收报表"), choice.candidates().stream().map(r -> r.reportName()).toList());
        assertTrue(h.previews.latest(USER1, "conversation-1").isEmpty(), "歧义时不能生成预览");

        Map<String, Object> result = map(tools.previewDispatchable("客户对账", null, null, null, context("conversation-1")));
        assertEquals("ambiguous", result.get("status"));
    }

    @Test
    void userCannotReachAReportWithoutPermissionByName() {
        // user3 没有应收报表权限：报表名、别名都识别不到，也不会被服务端兜底查询（T-RESOLVE-04、P0-04）
        List<ServerSentEvent<Object>> events = chat3("应收报表有哪些可以派单");
        assertTrue(events.stream().noneMatch(e -> "preview".equals(e.event()) || "choice".equals(e.event())));
        Map<String, Object> result = map(tools.previewDispatchable("应收", null, null, null, context3()));
        assertEquals("not_found", result.get("status"));
        assertFalse(String.valueOf(result.get("message")).contains("应收"), "不能泄露报表是否存在");
        // “客户对账”对 user3 只剩销售报表一个候选，直接查销售
        assertEquals("ok", map(tools.previewDispatchable("客户对账", null, null, null, context3())).get("status"));
        assertEquals(List.of(SALES), scope(USER3, "conversation-3"));
    }

    @Test
    void disabledReportIsNoLongerResolved() {
        h.catalog.replace(TestCatalog.with(h.catalog.get(EXPENSE), 2, "DISABLED", true));
        assertEquals("not_found", map(tools.previewDispatchable("费用报表", null, null, null, context("conversation-1"))).get("status"));
        h.catalog.replace(TestCatalog.with(h.catalog.get(SALES), 2, "PUBLISHED", false));
        assertEquals("not_found", map(tools.previewDispatchable("销售报表", null, null, null, context("conversation-1"))).get("status"),
                "关闭派单的报表不能通过 Agent 派单");
    }

    @Test
    void newReportIsRecognizedWithoutAnyCodeChange() {
        // 新增报表只靠目录数据：正则、枚举、提示词都不用改（P0-01 验收）
        CurrentUser buyer = new CurrentUser("T001", "user1", "用户1", USER1.companies(),
                java.util.Set.of("report:sales", "report:receivable", "report:expense", "report:purchase"), false);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.resolve("user1")).thenReturn(buyer);
        ConversationService conversations = mock(ConversationService.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        when(conversations.getOwned(any(CurrentUser.class), eq("conversation-1"))).thenReturn(conversation);
        DispatchTools buyerTools = new DispatchTools(permissions, h.previews, h.plans, mock(DispatchService.class),
                h.catalogService, conversations, h.props);
        AgentChatService buyerChat = new AgentChatService(client, conversations, mock(ChatModel.class), buyerTools, h.catalogService);
        h.catalog.add(TestCatalog.purchase());
        Candidate po = candidate(TestCatalog.PURCHASE, "7", "PO-2026-0001", "A", "某某电子");
        h.put(TestCatalog.PURCHASE, po);

        preview(buyerChat.chat(buyer, "conversation-1", "应收报表", List.of(), null).collectList().block(Duration.ofSeconds(5)));
        PreviewPayload fresh = preview(buyerChat.chat(buyer, "conversation-1", "加上采购报表", List.of(), null)
                .collectList().block(Duration.ofSeconds(5)));
        assertEquals(List.of(po), fresh.records());
        assertEquals(List.of(RECEIVABLE, TestCatalog.PURCHASE), scope(buyer, "conversation-1"));
    }

    @Test
    void recognizesPreviewClaimsThatNeedALocalCorrection() {
        assertTrue(AgentChatService.claimsPreviewRefreshed("预览已重新生成，含应收 + 费用："));
        assertTrue(AgentChatService.claimsPreviewRefreshed("已经按新的范围重新查询。\n预览已刷新。"));
        assertFalse(AgentChatService.claimsPreviewRefreshed("已重新查询应收报表，共 2 条可派单记录。最新预览见卡片。"));
        assertFalse(AgentChatService.claimsPreviewRefreshed("本轮没有生成新的预览，上方卡片仍是上一次的结果。"));
        // 工具报错原样转述时不是“声称已刷新”
        assertFalse(AgentChatService.claimsPreviewRefreshed("操作没有完成：预览已失效：报表目录已变更，请重新查询"));
    }

    @Test
    void recognizesRecheckClaimsThatNeedALocalCorrection() {
        assertTrue(AgentChatService.claimsRechecked("已重查应收报表：0 条可派单记录。预览编号 63c4f869bd97fa63adc47590d5ad3ae9。"));
        assertTrue(AgentChatService.claimsRechecked("已重新查询应收报表，共 4 条。"));
        assertFalse(AgentChatService.claimsRechecked("我建议重新查询一次应收报表。"));
        assertFalse(AgentChatService.claimsRechecked("需要重新查询应收报表后再派单。"));
        assertFalse(AgentChatService.claimsRechecked("本轮没有生成新的预览。"));
    }

    @Test
    void hallucinatedPreviewIdNeverReachesTheUser() {
        stubReply("已重查应收报表：0 条可派单记录。预览编号 `63c4f869bd97fa63adc47590d5ad3ae9`。");
        List<ServerSentEvent<Object>> events = chat("应收报表再查下");
        String text = text(events);
        assertFalse(text.contains("63c4f869bd97fa63adc47590d5ad3ae9"), "编造的编号不能展示给用户");
        assertTrue(text.contains("（见下方卡片）"), "编号被替换成了指路文案");
        assertEquals(0, preview(events).total());
        assertTrue(text.contains("不是本轮查询得到的"));
        assertFalse(text.contains("本轮没有生成新的预览"), "卡片已经被兜底刷新，就不能再说卡片还是旧的");
    }

    @Test
    void modelClaimingZeroWhileFallbackFindsRecordsIsCorrected() {
        h.put(RECEIVABLE, candidate(RECEIVABLE, "7", "INV-2026-0007", "A", "天津某某"));
        stubReply("应收报表重查结果：当前 0 条可派单记录。上次预览的 4 条现在已经查不到了。");
        List<ServerSentEvent<Object>> events = chat("应收报表再查下");
        String text = text(events);
        assertTrue(text.contains("0 条"), "模型原文要保留，不能改写");
        assertEquals(1, preview(events).total(), "卡片是服务端兜底查出来的真实结果");
        assertTrue(text.contains("不是本轮查询得到的"), "必须指出条数未经本轮查询");
    }

    @Test
    void fabricatedPreviewIdGetsALocalCorrectionWhenNothingWasProduced() {
        stubReply("已重查应收报表：0 条可派单记录。预览编号 `63c4f869bd97fa63adc47590d5ad3ae9`。两张报表现在都是空的。");
        String text = text(chat("你好"));
        assertFalse(text.contains("63c4f869bd97fa63adc47590d5ad3ae9"));
        assertTrue(text.contains("不是本轮生成的预览"), "必须明确告诉用户这个编号不可信");
        assertTrue(text.contains("本轮没有生成新的预览"), "必须告诉用户卡片仍是上一次的结果");
    }

    @Test
    void refreshDoesNotInvalidateAnotherConversation() {
        String otherPreview = (String) map(tools.previewDispatchable(null, null, null, null, context("conversation-2"))).get("previewId");
        String otherPlan = (String) map(tools.dispatch(null, null, context("conversation-2"))).get("planId");
        chat("我说销售报表");
        assertEquals(DispatchPreview.ACTIVE, previewStatus(otherPreview));
        assertEquals(DispatchPlan.PENDING, planStatus(otherPlan));
        assertEquals("error", map(tools.dispatch(otherPreview, null, context("conversation-1"))).get("status"));
    }

    @Test
    void oldCheckboxExclusionsDoNotPollutePreviewCreatedInSameTurn() {
        AgentEventChannel channel = new AgentEventChannel();
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(AgentEventChannel.CONTEXT_KEY, channel);
        data.put(ToolContextKeys.UI_EXCLUDES, List.of(expense.docNo()));
        ToolContext ctx = new ToolContext(data);
        tools.previewDispatchable("销售报表", null, null, null, ctx);
        Map<String, Object> pending = map(tools.dispatch(null, null, ctx));
        assertEquals("pending_confirm", pending.get("status"));
        assertEquals(1, pending.get("count"));
        assertEquals(List.of(), pending.get("excluded"));
    }

    @Test
    void currentCheckboxExclusionsAreStillApplied() {
        PreviewPayload current = preview(chat("全部报表"));
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(ToolContextKeys.UI_EXCLUDES, List.of(expense.docNo()));
        data.put(ToolContextKeys.UI_PREVIEW_ID, current.previewId());
        Map<String, Object> pending = map(tools.dispatch(null, null, new ToolContext(data)));
        assertEquals(1, pending.get("count"));
        assertEquals(List.of(expense.docNo()), pending.get("excluded"));
    }

    @Test
    void checkboxExclusionsFromAnotherCardAreRejected() {
        PreviewPayload old = preview(chat("全部报表"));
        preview(chat("全部报表"));
        // 勾选发生在旧卡片上：应拒绝，不能悄悄对新预览全选。
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(ToolContextKeys.UI_EXCLUDES, List.of(expense.docNo()));
        data.put(ToolContextKeys.UI_PREVIEW_ID, old.previewId());
        Map<String, Object> pending = map(tools.dispatch(null, null, new ToolContext(data)));
        assertEquals("error", pending.get("status"));
        assertTrue(h.store.plans().pending("conversation-1").isEmpty());
    }

    @Test
    void excludesMustComeFromThePreview() {
        preview(chat("全部报表"));
        Map<String, Object> result = map(tools.dispatch(null, List.of("NOPE-1"), context("conversation-1")));
        assertEquals("error", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("NOPE-1"));
    }

    @Test
    void preciseSelectionSurvivesChatToolContextWithDuplicateDocumentNumbers() {
        h.put(EXPENSE, candidate(EXPENSE, "1", sale.docNo(), "A", "expense"));
        var snapshot = preview(chat("全部报表"));
        reply = ctx -> Flux.defer(() -> {
            var result = map(tools.dispatch(null, null, ctx));
            assertEquals(1, result.get("count"));
            return Flux.just("已生成待确认清单");
        });
        chat.chat(USER1, "conversation-1", "把已勾选的记录帮我派单", List.of(), snapshot.previewId(),
                List.of(new com.example.report.dispatch.RecordKey(SALES, "1")))
                .collectList().block(Duration.ofSeconds(5));
        var pending = h.store.plans().pending("conversation-1");
        assertEquals(1, pending.size());
        var items = h.store.plans().items(pending.get(0).getId());
        assertEquals(1, items.size());
        assertEquals(EXPENSE, items.get(0).getReportId());
    }

    @Test
    void preciseSelectionFromAnOldPreviewIsRejected() {
        var old = preview(chat("全部报表"));
        preview(chat("全部报表"));
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(ToolContextKeys.UI_PREVIEW_ID, old.previewId());
        data.put(ToolContextKeys.UI_EXCLUDED_RECORDS,
                List.of(new com.example.report.dispatch.RecordKey(SALES, "1")));
        var result = map(tools.dispatch(null, null, new ToolContext(data)));
        assertEquals("error", result.get("status"));
        assertTrue(h.store.plans().pending("conversation-1").isEmpty());
    }

    @Test
    void allSelectedOldPreviewCannotFallBackToLatestWhenModelOmitsId() {
        var old = preview(chat("全部报表"));
        preview(chat("销售报表"));
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(ToolContextKeys.UI_PREVIEW_ID, old.previewId());
        data.put(ToolContextKeys.UI_EXCLUDED_RECORDS, List.of());
        assertEquals("error", map(tools.dispatch(null, null, new ToolContext(data))).get("status"));
        assertTrue(h.store.plans().pending("conversation-1").isEmpty());
    }

    @Test
    void allSelectedSourceCannotBeOverriddenByModel() {
        var old = preview(chat("全部报表"));
        var current = preview(chat("销售报表"));
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(ToolContextKeys.UI_PREVIEW_ID, old.previewId());
        assertEquals("error", map(tools.dispatch(current.previewId(), null, new ToolContext(data))).get("status"));
        assertTrue(h.store.plans().pending("conversation-1").isEmpty());
    }

    @Test
    void currentAllSelectedSourceWorksWhenModelOmitsId() {
        var current = preview(chat("全部报表"));
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(ToolContextKeys.UI_PREVIEW_ID, current.previewId());
        var result = map(tools.dispatch(null, null, new ToolContext(data)));
        assertEquals("pending_confirm", result.get("status"));
        assertEquals(2, result.get("count"));
        assertEquals(current.previewId(), h.store.plans().pending("conversation-1").get(0).getPreviewId());
    }

    @Test
    void unauthorizedExplicitCompanyDoesNotFallBackToDefaultCompanies() {
        Map<String, Object> result = map(tools.previewDispatchable(null, "B", null, null, context("conversation-1")));
        assertEquals("error", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("无权查看 B 公司"));
        verify(h.candidates, never()).findCandidates(any(), anySet(), anyList());
    }

    @Test
    void companyCorrectionAfterDeniedBQueriesASalesWithoutModelPermissionGuessing() {
        PreviewPayload old = preview(chat("全部报表"));
        String oldPlan = (String) map(tools.dispatch(null, null, context("conversation-1"))).get("planId");
        clearInvocations(client);
        stubReply("你说的 A 公司不在我这边可查询的范围内，无法切换到 A 公司查看。");

        var denied = chat("我在B公司有吗");
        assertTrue(text(denied).contains("无权查看 B 公司"));
        assertTrue(denied.stream().noneMatch(e -> "preview".equals(e.event()) || "preview_job".equals(e.event())));
        assertEquals(DispatchPreview.ACTIVE, previewStatus(old.previewId()));

        var corrected = chat("A公司销售报表的");
        assertEquals(List.of(sale), preview(corrected).records());
        assertEquals(List.of(SALES), latestScope());
        assertTrue(text(corrected).contains("A 公司"));
        assertFalse(text(corrected).contains("无法切换"));
        assertFalse(text(corrected).contains("系统提示"));
        assertEquals(DispatchPreview.SUPERSEDED, previewStatus(old.previewId()));
        assertEquals(DispatchPlan.EXPIRED, planStatus(oldPlan));
        verify(client, never()).prompt();
    }

    @Test
    void explicitCompanyResultIsPersistedAndAddedToMemory() {
        var memory = mock(org.springframework.ai.chat.memory.ChatMemory.class);
        org.springframework.test.util.ReflectionTestUtils.setField(chat, "chatMemory", memory);
        var events = chat("A公司销售报表的");
        String result = text(events);
        verify(conversations).logUser("conversation-1", "user1", "A公司销售报表的");
        verify(conversations).logAssistant(eq("conversation-1"), eq("user1"), eq(result), isNull(), isNull(), anyLong());
        verify(memory).add(eq("conversation-1"), argThat((org.springframework.ai.chat.messages.Message m) ->
                m instanceof org.springframework.ai.chat.messages.UserMessage && m.getText().equals("A公司销售报表的")));
        verify(memory).add(eq("conversation-1"), argThat((org.springframework.ai.chat.messages.Message m) ->
                m instanceof org.springframework.ai.chat.messages.AssistantMessage && m.getText().equals(result)));
    }

    @Test
    void explicitCompanyStillQueriesWhenMemoryIsUnavailable() {
        var memory = mock(org.springframework.ai.chat.memory.ChatMemory.class);
        doThrow(new IllegalStateException("memory unavailable")).when(memory)
                .add(anyString(), any(org.springframework.ai.chat.messages.Message.class));
        org.springframework.test.util.ReflectionTestUtils.setField(chat, "chatMemory", memory);
        assertEquals(List.of(sale), preview(chat("A公司销售报表的")).records());
    }

    @Test
    void companyPermissionIsCheckedBeforeSubmittingAsyncPreview() {
        var jobs = mock(com.example.report.dispatch.PreviewJobService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "previewJobs", jobs);
        AgentEventChannel channel = new AgentEventChannel();
        Map<String, Object> context = new HashMap<>(context("conversation-1").getContext());
        context.put(AgentEventChannel.CONTEXT_KEY, channel);
        var outcome = map(tools.previewDispatchable("销售报表", "B", null, null, new ToolContext(context)));
        assertEquals("error", outcome.get("status"));
        assertTrue(String.valueOf(outcome.get("message")).contains("无权查看 B 公司"));
        verifyNoInteractions(jobs);
        assertFalse(channel.hasEmitted(AgentEvent.PREVIEW_JOB));
    }

    @Test
    void authorizedCompanyQuerySubmitsExactScopeAndReportsOnlyPendingStatus() {
        var jobs = mock(com.example.report.dispatch.PreviewJobService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "previewJobs", jobs);
        var now = java.time.LocalDateTime.now();
        when(jobs.submit(eq(USER1), eq("conversation-1"), any())).thenReturn(
                new com.example.report.dispatch.PreviewJobService.Job("job-a", "RUNNING", "QUERY", 0, null, null, now, now));
        var events = chat("A公司销售报表的");
        assertTrue(events.stream().anyMatch(e -> "preview_job".equals(e.event())));
        assertFalse(events.stream().anyMatch(e -> "preview".equals(e.event())));
        assertTrue(text(events).contains("正在查询 A 公司"));
        assertFalse(text(events).contains("共 "));
        verify(jobs).submit(eq(USER1), eq("conversation-1"), argThat(command ->
                "A".equals(command.filters().companyCode()) && "销售报表".equals(command.reportQuery())));
        verify(client, never()).prompt();
    }

    @Test
    void explicitCompanyDoesNotGrantAnotherUsersCompanyAccess() {
        var events = chat3("A公司销售报表的");
        assertTrue(text(events).contains("无权查看 A 公司"));
        assertTrue(events.stream().noneMatch(e -> "preview".equals(e.event()) || "preview_job".equals(e.event())));
        verify(client, never()).prompt();
    }

    @Test
    void rulePublishedWhilePreviewIsComputingInvalidatesThatPreview() {
        // 预览求值期间有人发布了新规则：候选是按旧规则算的，快照必须带旧版本，派单时才会被拦下
        when(h.candidates.findCandidates(anyString(), anySet(), anyList())).thenAnswer(call -> {
            h.ruleVersion.set("rules-v2");
            return List.of(sale);
        });
        tools.previewDispatchable("销售报表", null, null, null, context("conversation-1"));
        Map<String, Object> result = map(tools.dispatch(null, null, context("conversation-1")));
        assertEquals("error", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("规则已更新"));
    }

    @Test
    void explicitRemoveScopeSubtractsEvenWhenServerDidNotRecognizeRemoval() {
        // "应收的那些也不要了" 服务端识别不出排除语义；模型按提示传 应收 + scopeMode=remove，仍然要从上一轮范围里减掉应收
        tools.previewDispatchable("销售、应收、费用", null, null, null, context("conversation-1"));
        Map<String, Object> result = map(tools.previewDispatchable("应收", null, null, "remove", context("conversation-1")));
        assertEquals("ok", result.get("status"));
        assertEquals(List.of(SALES, EXPENSE), latestScope());
    }

    @Test
    void explicitReplaceScopeWinsOverServerRecognizedRemoval() {
        tools.previewDispatchable("销售、应收、费用", null, null, null, context("conversation-1"));
        Map<String, Object> data = new HashMap<>(context("conversation-1").getContext());
        data.put(ToolContextKeys.PREVIEW_REMOVE, true);
        tools.previewDispatchable("销售、费用", null, null, "replace", new ToolContext(data));
        assertEquals(List.of(SALES, EXPENSE), latestScope());
    }

    @Test
    void unknownScopeModeIsRejected() {
        assertEquals("error", map(tools.previewDispatchable("销售报表", null, null, "minus", context("conversation-1"))).get("status"));
    }

    @Test
    void modelThatAlreadyCalledTheToolIsNotSecondGuessedByTheFallback() {
        // 模型调用了预览工具但得到 not_found：服务端不再按自己的识别再查一次
        reply = ctx -> Flux.defer(() -> {
            tools.previewDispatchable("不存在的报表", null, null, null, ctx);
            return Flux.just("没有找到匹配的报表。");
        });
        List<ServerSentEvent<Object>> events = chat("销售报表");
        assertTrue(events.stream().noneMatch(e -> "preview".equals(e.event())));
    }

    // ---------- 工具方法 ----------

    /** 模型本轮的回复（入参是本轮真实的 ToolContext） */
    private java.util.function.Function<ToolContext, Flux<String>> reply;

    private void stubReply(String text) {
        reply = ctx -> Flux.just(text);
    }

    private static String text(List<ServerSentEvent<Object>> events) {
        return events.stream().filter(e -> "text".equals(e.event()))
                .map(e -> String.valueOf(((Map<?, ?>) e.data()).get("delta")))
                .collect(java.util.stream.Collectors.joining());
    }

    private List<ServerSentEvent<Object>> chat(String message) {
        return chat.chat(USER1, "conversation-1", message, List.of(), null).collectList().block(Duration.ofSeconds(5));
    }

    private List<ServerSentEvent<Object>> chat3(String message) {
        return chat.chat(USER3, "conversation-1", message, List.of(), null).collectList().block(Duration.ofSeconds(5));
    }

    private List<String> latestScope() {
        return scope(USER1, "conversation-1");
    }

    /** 本会话最近一次预览的报表范围 */
    private List<String> scope(CurrentUser user, String conversationId) {
        return DispatchVersionService.reportIds(h.previews.latest(user, conversationId).orElseThrow());
    }

    private String previewStatus(String previewId) {
        return h.store.previews().find(previewId).orElseThrow().getStatus();
    }

    private String planStatus(String planId) {
        return h.store.plans().find(planId).orElseThrow().getStatus();
    }

    private static PreviewPayload preview(List<ServerSentEvent<Object>> events) {
        assertEquals(1, events.stream().filter(e -> "preview".equals(e.event())).count(), events.toString());
        return events.stream().filter(e -> "preview".equals(e.event())).map(e -> (PreviewPayload) e.data()).findFirst().orElseThrow();
    }

    private static ToolContext context(String conversationId) {
        return new ToolContext(Map.of(ToolContextKeys.USER_ID, "user1", ToolContextKeys.CONVERSATION_ID, conversationId));
    }

    private static ToolContext context3() {
        return new ToolContext(Map.of(ToolContextKeys.USER_ID, "user3", ToolContextKeys.CONVERSATION_ID, "conversation-3"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object result) {
        return (Map<String, Object>) result;
    }

}
