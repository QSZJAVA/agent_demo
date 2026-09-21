package com.example.report.agent;

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
    private final ChatMemory chatMemory;
    private final String modelName;

    public AgentChatService(ChatClient chatClient, ConversationService conversationService, ChatModel chatModel,
                            DispatchTools dispatchTools, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.conversationService = conversationService;
        this.dispatchTools = dispatchTools;
        this.chatMemory = chatMemory;
        String name = null;
        try {
            name = chatModel.getDefaultOptions() == null ? null : chatModel.getDefaultOptions().getModel();
        } catch (Exception ignored) {
        }
        this.modelName = name == null ? chatModel.getClass().getSimpleName() : name;
    }

    public String modelName() {
        return modelName;
    }

    public Flux<ServerSentEvent<Object>> chat(CurrentUser user, String conversationId, String message, List<String> uiExcludes) {
        AgentConversation conversation = (conversationId == null || conversationId.isBlank())
                ? conversationService.create(user, modelName)
                : conversationService.getOwned(user, conversationId);
        String convId = conversation.getId();

        AgentEventChannel channel = new AgentEventChannel();
        var previewRequest = ReportPreviewRequest.resolve(message);
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ToolContextKeys.USER_ID, user.userId());
        toolContext.put(ToolContextKeys.CONVERSATION_ID, convId);
        toolContext.put(ToolContextKeys.UI_EXCLUDES, uiExcludes == null ? List.of() : List.copyOf(uiExcludes));
        toolContext.put(AgentEventChannel.CONTEXT_KEY, channel);
        // 服务端识别出的"追加 / 排除报表"语义一并注入，让模型发起的工具调用也带上精确范围；
        // 是否真的需要服务端兜底，则等本轮结束、确认模型没调工具之后再决定。
        previewRequest.ifPresent(r -> {
            toolContext.put(ToolContextKeys.PREVIEW_APPEND, r.append());
            toolContext.put(ToolContextKeys.PREVIEW_REMOVE, r.remove());
        });

        StringBuilder reply = new StringBuilder();
        // 模型偶尔会把预览编号这类内部标识写进回复，甚至编造一个系统里根本不存在的编号，
        // 用户看到只会被误导（预览本来已经由界面卡片完整展示），所以在流式出口做一次掩码。
        PreviewIdMask previewIdMask = new PreviewIdMask();
        // 所有消息统一交给模型；明确的查询 / 范围纠正不再由服务端抢答
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
        // 2) 补不出来（或没识别出范围）而模型又声称已刷新 / 已生成清单时，补一句纠正；
        // 3) 最后关闭事件通道，让上面的合并流收尾。
        Flux<AgentEvent> guard = Flux.defer(() -> {
            List<AgentEvent> hints = new java.util.ArrayList<>();
            // 掩码器可能还攥着半截编号没放行，先收尾，保证 text 与实际展示给用户的文本一致
            for (String tail : previewIdMask.flush()) {
                reply.append(tail);
                hints.add(new AgentEvent(AgentEvent.TEXT, Map.of("delta", tail)));
            }
            String text = reply.toString();
            boolean previewEmitted = channel.hasEmitted(AgentEvent.PREVIEW);
            if (!previewEmitted && previewRequest.isPresent()
                    && !channel.hasEmitted(AgentEvent.PLAN) && !channel.hasEmitted(AgentEvent.RESULT)) {
                fallbackPreview(convId, previewRequest.get(), toolContext);
                previewEmitted = channel.hasEmitted(AgentEvent.PREVIEW);
            }
            // 模型提到了预览编号，但本轮要么没有预览、要么该编号不是本轮生成的 → 一定是编的
            Set<String> maskedIds = previewIdMask.maskedIds();
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
        Flux<AgentEvent> head = Flux.just(new AgentEvent(AgentEvent.CONVERSATION, headData));
        Flux<AgentEvent> tail = Flux.just(new AgentEvent(AgentEvent.DONE, Map.of()));

        // guard 可能在文本流之后再往 channel 里补一个 PREVIEW 事件，
        // 所以必须等 guard 结束（其 doFinally 里 complete）合并流才会收尾。
        Flux<AgentEvent> body = textEvents.concatWith(guard);
        return head.concatWith(Flux.merge(channel.asFlux(), body)).concatWith(tail)
                .map(e -> ServerSentEvent.builder((Object) e.data()).event(e.type()).build());
    }

    /**
     * 服务端兜底：模型该刷新预览却没调用 previewDispatchable 时，
     * 用服务端已识别的报表范围补生成一次真实预览（会 emit PREVIEW 事件并落库）。
     */
    private void fallbackPreview(String conversationId, ReportPreviewRequest request, Map<String, Object> toolContext) {
        try {
            Object result = dispatchTools.previewDispatchable(String.join(",", request.reportTypes()),
                    request.companyCode(), null, new ToolContext(toolContext));
            if (result instanceof Map<?, ?> m && "ok".equals(m.get("status"))) {
                log.info("模型未生成预览，服务端已按识别到的范围补生成 conversation={} reportTypes={}",
                        conversationId, request.reportTypes());
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
    /** 模型没调工具却声称预览已刷新时的兜底提示 */
    private static final String PREVIEW_CLAIM_HINT =
            "\n\n（系统提示：本轮没有生成新的预览，上方卡片仍是上一次查询的结果。"
                    + "请重新说明要查询的报表范围（例如只看应收报表、加上费用报表的），或让我重新查询可派单记录。）";
    /** 模型提到了本轮不存在的预览编号时的兜底提示 */
    private static final String PREVIEW_ID_HINT =
            "\n\n（系统提示：上一条回复提到的预览编号不是本轮生成的预览，请以界面上的预览卡片为准。）";
    private static final Pattern PLAN_CLAIM = Pattern.compile("已.{0,60}?(生成|发起|更新|创建|提交)");
    /** 完成态表述：已重查 / 已重新查询 / 已刷新… 用于识别"没调工具却宣称查过了" */
    private static final Pattern RECHECK_CLAIM =
            Pattern.compile("(?:已经|已)(?:重新|再次)?(?:重查|查(?:询|过)?|刷新|更新|生成|载入|预览|拉取)");
    private static final Pattern PREVIEW_CLAIM =
            Pattern.compile("(?:已|重新|再次)(?:重新)?(?:生成|刷新|更新|载入|预览|查询)");
    private static final List<String> PLAN_CLAIM_NEGATIONS =
            List.of("未生成", "没有生成", "无法生成", "未调用", "已过期", "已取消", "已执行");
    private static final List<String> PREVIEW_CLAIM_NEGATIONS =
            List.of("未生成", "没有生成", "无法生成", "未刷新", "不会生成", "不能生成", "已作废");

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

    private static String friendly(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("invalid_api_key")) {
            return "大模型鉴权失败：请配置环境变量 LLM_API_KEY（或使用 --spring.profiles.active=mock 本地演示）";
        }
        return "模型调用失败：" + msg;
    }
}
