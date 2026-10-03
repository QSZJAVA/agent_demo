package com.example.report.agent;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.TraceIds;
import com.example.report.config.ResourceQuotaService;
import com.example.report.conversation.ConversationService;
import com.example.report.entity.AgentConversation;
import com.example.report.permission.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对话入口：把用户 ID 与会话 ID 放进 ToolContext 和 Advisor 参数，
 * 把模型文本流与工具发出的结构化事件合并成一条 SSE 流。
 */
@Slf4j
@Service
public class AgentChatService {

    private final ChatClient chatClient;
    private final ConversationService conversationService;
    private final DispatchTools dispatchTools;
    private final ReportCatalogService catalogService;
    private final String modelName;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ResourceQuotaService quotas;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.report.dispatch.PreviewService previewService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatMemory chatMemory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.report.semantic.SemanticConversationService semantic;

    public AgentChatService(ChatClient chatClient, ConversationService conversationService, ChatModel chatModel,
                            DispatchTools dispatchTools, ReportCatalogService catalogService) {
        this.chatClient = chatClient;
        this.conversationService = conversationService;
        this.dispatchTools = dispatchTools;
        this.catalogService = catalogService;
        String name = null;
        try {
            name = chatModel.getDefaultOptions() == null ? null : chatModel.getDefaultOptions().getModel();
        } catch (Exception ignored) {
        }
        this.modelName = name == null ? chatModel.getClass().getSimpleName() : name;
    }

    public String modelName() {
        return semantic != null && semantic.enabled() ? semantic.modelName(modelName) : modelName;
    }

    /**
     * @param uiExcludes  前端预览表格里取消勾选的单据号
     * @param uiPreviewId 取消勾选发生在哪张预览卡片上；只有与本轮派单用的预览一致时勾选项才生效
     */
    public Flux<ServerSentEvent<Object>> chat(CurrentUser user, String conversationId, String message,
                                              List<String> uiExcludes, String uiPreviewId) {
        return chat(user, conversationId, message, uiExcludes, uiPreviewId, List.of());
    }

    public Flux<ServerSentEvent<Object>> chat(CurrentUser user, String conversationId, String message,
                                              List<String> uiExcludes, String uiPreviewId,
                                              List<com.example.report.dispatch.RecordKey> excludedRecords) {
        if (semantic != null && semantic.enabled())
            return semantic.chat(user, conversationId, message, uiExcludes, uiPreviewId, excludedRecords, modelName());
        return legacyChat(user,conversationId,com.example.report.operations.SensitiveData.text(message),uiExcludes,uiPreviewId,excludedRecords);
    }

    private Flux<ServerSentEvent<Object>> legacyChat(CurrentUser user,String conversationId,String message,
            List<String> uiExcludes,String uiPreviewId,List<com.example.report.dispatch.RecordKey> excludedRecords) {
        AgentConversation conversation = (conversationId == null || conversationId.isBlank())
                ? conversationService.create(user, modelName)
                : conversationService.getOwned(user, conversationId);
        String convId = conversation.getId();
        if (previewService != null) previewService.requireConversationReadable(user, convId);

        AgentEventChannel channel = new AgentEventChannel();
        // 报表说法只在当前用户可派单的目录范围内识别，不可见报表的名称不会触发兜底查询
        var previewIntent = PreviewIntentDetector.detect(message, catalogService.terms(), catalogService.dispatchableIds(user));
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ToolContextKeys.USER_ID, user.userId());
        toolContext.put(ToolContextKeys.CONVERSATION_ID, convId);
        toolContext.put(ToolContextKeys.UI_EXCLUDES, uiExcludes == null ? List.of() : List.copyOf(uiExcludes));
        toolContext.put(ToolContextKeys.UI_EXCLUDED_RECORDS, excludedRecords == null ? List.of() : List.copyOf(excludedRecords));
        if (uiPreviewId != null && !uiPreviewId.isBlank()) {
            toolContext.put(ToolContextKeys.UI_PREVIEW_ID, uiPreviewId);
        }
        String traceId = TraceIds.current();
        if (traceId != null) {
            toolContext.put(ToolContextKeys.TRACE_ID, traceId);
        }
        toolContext.put(AgentEventChannel.CONTEXT_KEY, channel);
        // 服务端识别出的"追加 / 排除报表"语义一并注入，让模型发起的工具调用也带上精确范围；
        // 是否真的需要服务端兜底，则等本轮结束、确认模型没调工具之后再决定。
        previewIntent.ifPresent(r -> {
            toolContext.put(ToolContextKeys.PREVIEW_APPEND, r.append());
            toolContext.put(ToolContextKeys.PREVIEW_REMOVE, r.remove());
        });

        StringBuilder reply = new StringBuilder();
        // 模型偶尔会把预览编号这类内部标识写进回复，甚至编造一个系统里根本不存在的编号，
        // 用户看到只会被误导（预览本来已经由界面卡片完整展示），所以在流式出口做一次掩码。
        PreviewIdMask previewIdMask = new PreviewIdMask();
        var sensitiveStream = new com.example.report.operations.SensitiveTextStream();
        // 其余意图交给模型；明确公司且已完整识别的纯查询在下方走确定性路径。
        Flux<String> response = Flux.defer(() -> chatClient.prompt()
                .user(message)
                .toolContext(toolContext)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId)
                        .param(ConversationLogAdvisor.USER_ID_PARAM, user.userId()))
                .stream()
                .content());
        Flux<AgentEvent> textEvents = response
                .filter(s -> s != null && !s.isEmpty())
                .concatMap(s -> Flux.fromIterable(previewIdMask.feed(s)))
                .map(sensitiveStream::feed)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    reply.append(s);
                    return new AgentEvent(AgentEvent.TEXT, Map.of("delta", s));
        })
                .onErrorResume(e -> {
                    log.error("模型调用失败 conversation={}", convId, e);
                    return Flux.just(new AgentEvent(AgentEvent.ERROR, Map.of("message", friendly(e))));
        });

        // 文本输出结束后执行，顺序很关键：
        // 1) 模型该刷新预览却没调工具时，服务端用已识别的范围补一次真实预览，保证卡片一定刷新；
        // 2) 模型没调工具却给出了结论时补一句纠正：卡片已兜底补出，就指出文字里的条数不是本轮查的；
        //    补不出来（或没识别出范围）而模型又声称已刷新 / 已生成清单，就说明卡片仍是旧的；
        // 3) 最后关闭事件通道，让上面的合并流收尾。
        Flux<AgentEvent> guard = Flux.defer(() -> {
            List<AgentEvent> hints = new java.util.ArrayList<>();
            // 掩码器可能还攥着半截编号没放行，先收尾，保证 text 与实际展示给用户的文本一致
            for (String tail : previewIdMask.flush()) {
                String safe = sensitiveStream.feed(tail);
                reply.append(safe);
                hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta", safe)));
            }
            String safeTail = sensitiveStream.flush();
            reply.append(safeTail);
            if (!safeTail.isEmpty()) hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta",safeTail)));
            String text = reply.toString();
            boolean previewEmitted = channel.hasEmitted(AgentEvent.PREVIEW)
                    || channel.hasEmitted(AgentEvent.PREVIEW_JOB);
            boolean fallbackEmitted = false;
            // 模型调过预览工具（哪怕结果是歧义或没找到）就说明它处理了这个意图，服务端不再重复查询
            if (!previewEmitted && previewIntent.isPresent() && !channel.toolCalled(DispatchTools.TOOL_PREVIEW)
                    && !channel.hasEmitted(AgentEvent.CHOICE)
                    && !channel.hasEmitted(AgentEvent.PREVIEW_JOB)
                    && !channel.hasEmitted(AgentEvent.PLAN) && !channel.hasEmitted(AgentEvent.RESULT)) {
                fallbackPreview(convId, previewIntent.get(), toolContext);
                previewEmitted = channel.hasEmitted(AgentEvent.PREVIEW)
                        || channel.hasEmitted(AgentEvent.PREVIEW_JOB);
                fallbackEmitted = previewEmitted || channel.hasEmitted(AgentEvent.CHOICE);
            }
            Set<String> maskedIds = previewIdMask.maskedIds();
            if (channel.hasEmitted(AgentEvent.PREVIEW_JOB)
                    && (claimsQueryResult(text, maskedIds) || claimsPreviewRefreshed(text))) {
                hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta", QUERY_PENDING_HINT)));
            }
            // 卡片是服务端兜底补查的：模型根本没调工具，它文字里的条数、"已重查"都不是本轮查出来的
            if (fallbackEmitted && channel.hasEmitted(AgentEvent.PREVIEW)
                    && claimsQueryResult(text, maskedIds)) {
                log.warn("模型未调用工具却给出了查询结论，卡片由服务端兜底生成，已追加纠正提示 conversation={}", convId);
                hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta", FALLBACK_RESULT_HINT)));
            }
            // 模型提到了预览编号，但本轮要么没有预览、要么该编号不是本轮生成的 → 一定是编的
            boolean fabricatedPreviewId = !maskedIds.isEmpty()
                    && maskedIds.stream().anyMatch(id -> !channel.previewIds().contains(id));
            if (!previewEmitted && fabricatedPreviewId) {
                log.warn("模型提到了本轮不存在的预览编号，已追加纠正提示 conversation={} ids={}", convId, maskedIds);
                hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta", PREVIEW_ID_HINT)));
            }
            if (!channel.hasEmitted(AgentEvent.PLAN) && claimsPlanGenerated(text)) {
                log.warn("模型声称已生成派单清单但本轮未调用 dispatch，已追加纠正提示 conversation={}", convId);
                hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta", PLAN_CLAIM_HINT)));
            }
            boolean previewHintAdded = false;
            if (!previewEmitted && claimsPreviewRefreshed(text)) {
                log.warn("模型声称已重新生成预览但本轮未调用 previewDispatchable，已追加纠正提示 conversation={}", convId);
                hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta", PREVIEW_CLAIM_HINT)));
                previewHintAdded = true;
            }
            // "已重查应收报表…"这类完成态表述，本轮却连一张预览都没生成，同样是在编
            if (!previewHintAdded && !previewEmitted && !channel.hasEmitted(AgentEvent.PLAN) && claimsRechecked(text)) {
                log.warn("模型声称已重查报表但本轮没有任何预览，已追加纠正提示 conversation={}", convId);
                hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta", PREVIEW_CLAIM_HINT)));
            }
            return Flux.fromIterable(hints);
        }).subscribeOn(Schedulers.boundedElastic()).doFinally(signal -> channel.complete());

        Map<String, Object> headData = new java.util.LinkedHashMap<>();
        headData.put("conversationId", convId);
        headData.put("title", conversation.getTitle() == null ? "" : conversation.getTitle());
        String requestId = java.util.UUID.randomUUID().toString();
        headData.put("requestId", requestId);
        Flux<AgentEvent> head = Flux.just(new AgentEvent(AgentEvent.CONVERSATION, headData));
        Flux<AgentEvent> tail = Flux.just(new AgentEvent(AgentEvent.DONE, Map.of()));

        // guard 可能在文本流之后再往 channel 里补一个 PREVIEW 事件，
        // 所以必须等 guard 结束（其 doFinally 里 complete）合并流才会收尾。
        // 公司查询不让模型凭历史文本猜测权限，也不先展示错误拒绝再补一条系统提示。
        // 身份校验、预览工具、异步任务、会话日志与记忆仍使用同一套业务服务。
        Flux<AgentEvent> body = previewIntent.filter(intent -> intent.companyCode() != null)
                .map(intent -> companyPreview(user, convId, message, intent, toolContext)
                        .doFinally(signal -> channel.complete()))
                .orElseGet(() -> textEvents.concatWith(guard));
        return Flux.defer(() -> {
            ResourceQuotaService.Permit permit = quotas == null ? null : quotas.acquire(user, "chat", List.of());
            Flux<AgentEvent> events = head.concatWith(Flux.merge(channel.asFlux(), body)).concatWith(tail);
            return (permit == null ? events : permit.guard(events))
                    .index()
                    .map(event -> ServerSentEvent.builder(com.example.report.operations.SensitiveData.typed(event.getT2().data()))
                            .id(requestId + ":" + event.getT1())
                            .event(event.getT2().type()).build())
                    .doFinally(signal -> { if (permit != null) permit.close(); });
        });
    }

    private Flux<AgentEvent> companyPreview(CurrentUser user, String conversationId, String message,
                                            PreviewIntentDetector.PreviewIntent intent, Map<String, Object> context) {
        return Flux.defer(() -> {
            // 在用户消息落库之前回灌工作记忆，避免回灌时重复读取本轮消息。
            remember(conversationId, new org.springframework.ai.chat.messages.UserMessage(message));
            conversationService.logUser(conversationId, user.userId(), message);
            long started = System.nanoTime();
            Object outcome = dispatchTools.fallbackPreview(intent.reportQuery(), intent.companyCode(), new ToolContext(context));
            String text = companyPreviewReply(intent, outcome);
            remember(conversationId, new org.springframework.ai.chat.messages.AssistantMessage(text));
            conversationService.logAssistant(conversationId, user.userId(), text, null, null,
                    (System.nanoTime() - started) / 1_000_000);
            return Flux.just(new AgentEvent(AgentEvent.TEXT, Map.of("delta", text)));
        }).onErrorResume(error -> {
            log.error("公司范围预览失败 conversation={}", conversationId, error);
            return Flux.just(new AgentEvent(AgentEvent.ERROR, Map.of("message", "预览查询暂时失败，请稍后重试")));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static String companyPreviewReply(PreviewIntentDetector.PreviewIntent intent, Object outcome) {
        if (!(outcome instanceof Map<?, ?> result)) return "未能生成预览，请稍后重试。";
        String scope = intent.companyCode() + " 公司";
        return switch (String.valueOf(result.get("status"))) {
            case "querying" -> "正在查询 " + scope + " 的可派单记录，完成后会显示预览卡片。";
            case "ok" -> "已查询 " + scope + " 的可派单记录，共 " + result.get("total") + " 条，请查看新的预览卡片。";
            case "ambiguous" -> "找到多个相关报表，请在卡片上选择要查询的报表；公司范围为 " + scope + "。";
            case "error", "not_found" -> com.example.report.operations.SensitiveData.text(String.valueOf(result.get("message")));
            default -> "未能生成预览，请稍后重试。";
        };
    }

    private void remember(String conversationId, org.springframework.ai.chat.messages.Message message) {
        if (chatMemory == null) return;
        try {
            chatMemory.add(conversationId, message);
        } catch (RuntimeException failure) {
            log.warn("公司查询工作记忆写入失败，将从会话日志恢复 conversation={}", conversationId, failure);
        }
    }

    /**
     * 服务端兜底：模型该刷新预览却没调用 previewDispatchable 时，
     * 用服务端从报表目录识别出的范围补生成一次真实预览（会 emit PREVIEW / CHOICE 事件并落库）。
     */
    private void fallbackPreview(String conversationId, PreviewIntentDetector.PreviewIntent intent, Map<String, Object> toolContext) {
        try {
            Object result = dispatchTools.fallbackPreview(intent.reportQuery(), intent.companyCode(), new ToolContext(toolContext));
            if (result instanceof Map<?, ?> m && ("ok".equals(m.get("status")) || "ambiguous".equals(m.get("status")))) {
                log.info("模型未生成预览，服务端已按识别到的范围补查 conversation={} reportQuery={} status={}",
                        conversationId, intent.reportQuery(), m.get("status"));
            } else {
                log.warn("服务端补生成预览失败 conversation={} result={}", conversationId, result);
            }
        } catch (Exception e) {
            log.warn("服务端补生成预览异常 conversation={}", conversationId, e);
        }
    }

    /** 模型没调工具却声称已生成清单时的兜底提示 */
    private static final String PLAN_CLAIM_HINT =
            "\n\n（系统提示：本轮没有生成新的待确认清单，请重新说明派单意图，或让我重新查询一次可派单记录。）";
    /** 模型没调工具却声称预览已刷新时的兜底提示*/
    private static final String PREVIEW_CLAIM_HINT =
            "\n\n（系统提示：本轮没有生成新的预览，上方卡片仍是上一次查询的结果。"
                    + "请重新说明要查询的报表范围（例如只看某张报表、再加上另一张报表），或让我重新查询可派单记录。）";
    /** 模型提到了本轮不存在的预览编号时的兜底提示 */
    private static final String PREVIEW_ID_HINT =
            "\n\n（系统提示：上一条回复提到的预览编号不是本轮生成的预览，请以界面上的预览卡片为准。）";
    /** 服务端兜底补出了卡片、而模型没调工具却给出了查询结论时的提示*/
    private static final String FALLBACK_RESULT_HINT =
            "\n\n（系统提示：上面回复中的条数和结论不是本轮查询得到的，请以下方卡片为准。）";
    private static final String QUERY_PENDING_HINT =
            "\n\n（系统提示：预览查询仍在运行，上面的条数和结论尚未确认，请以查询完成后的卡片为准。）";
    /** 条数表述：0 条 / 共 4 条 */
    private static final Pattern COUNT_CLAIM = Pattern.compile("\\d+\\s*条");
    private static final Pattern PLAN_CLAIM = Pattern.compile("已.{0,60}?(生成|发起|更新|创建|提交)");
    /** 完成态表述：已重查 / 已重新查询 / 已刷新… 用于识别"没调工具却宣称查过了"*/
    private static final Pattern RECHECK_CLAIM =
            Pattern.compile("(?:已经|已)(?:重新|再次)?(?:重查|查(?:询|过)?|刷新|更新|生成|载入|预览|拉取)");
    private static final Pattern PREVIEW_CLAIM =
            Pattern.compile("(?:已|重新|再次)(?:重新)?(?:生成|刷新|更新|载入|预览|查询)");
    private static final List<String> PLAN_CLAIM_NEGATIONS =
            List.of("未生成", "没有生成", "无法生成", "未调用", "已过期", "已取消", "已执行", "已失效", "已作废");
    private static final List<String> PREVIEW_CLAIM_NEGATIONS =
            List.of("未生成", "没有生成", "无法生成", "未刷新", "不会生成", "不能生成", "已作废", "已失效", "已过期",
                    "请重新查询", "需要重新查询");

    /**
     * 文本里是否声称"已经生成 / 更新了清单"。
     * 按句判断：同一句里同时有"已 + 生成/发起/更新…"和"清单/派单"才算声称已完成，
     * 避免把「请在界面确认卡片上点击确认派单」这类正当提示误判。
     */
    private static boolean claimsPlanGenerated(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (PLAN_CLAIM_NEGATIONS.stream().anyMatch(text::contains)) {
            return false;
        }
        for (String sentence : text.split("[。！\\n]")) {
            if (PLAN_CLAIM.matcher(sentence).find() && (sentence.contains("清单") || sentence.contains("派单"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文本里是否声称"已经重新生成 / 刷新 / 查询了预览"。
     * 同一句里同时出现"预览"和"已/重新 + 生成/刷新/更新/预览/查询"才算，避免把"最新预览见卡片"这类正当提示误判。
     */
    static boolean claimsPreviewRefreshed(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (PREVIEW_CLAIM_NEGATIONS.stream().anyMatch(text::contains)) {
            return false;
        }
        for (String sentence : text.split("[。！\\n]")) {
            if (sentence.contains("预览") && PREVIEW_CLAIM.matcher(sentence).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文本里是否出现"已经完成一次查询/刷新"的完成态表述（如"已重查应收报表"）。
     * 与 claimsPreviewRefreshed 不同，这里不要求同一句里出现"预览"：
     * 模型常把"已重查…"和"预览编号…"写成两句，只按句判断会漏掉这种情况。
     */
    static boolean claimsRechecked(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (PREVIEW_CLAIM_NEGATIONS.stream().anyMatch(text::contains)) {
            return false;
        }
        return RECHECK_CLAIM.matcher(text).find();
    }

    /**
     * 文本里是否给出了查询结论：条数、"已重查 / 已刷新"之类的完成态，或提到了预览编号。
     * 只在服务端兜底补出卡片时使用——那时模型没调工具，这些结论都不是本轮查出来的。
     */
    static boolean claimsQueryResult(String text, Set<String> maskedIds) {
        if (!maskedIds.isEmpty()) {
            return true;
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        return COUNT_CLAIM.matcher(text).find() || claimsRechecked(text) || claimsPreviewRefreshed(text);
    }

    private static String friendly(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("invalid_api_key")) {
            return "大模型鉴权失败：请配置环境变量 LLM_API_KEY（或使用 --spring.profiles.active=mock 本地演示）";
        }
        return "模型调用失败：" + msg;
    }
}
