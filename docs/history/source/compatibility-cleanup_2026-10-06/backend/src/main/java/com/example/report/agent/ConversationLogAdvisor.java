package com.example.report.agent;

import com.example.report.conversation.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * 对话日志 Advisor：before 写用户消息，after 写助手回复。
 * 流式场景下 BaseAdvisor 默认只把最后一个分片交给 after，这里改为先聚合完整回复再写库（与 MessageChatMemoryAdvisor 相同做法）。
 * 工具调用与卡片由 DispatchTools 自己写。
 * 顺序排在记忆 Advisor 之后：记忆回灌（读 MySQL）先于本轮用户消息落库，避免回灌时把本轮消息重复读进工作记忆。
 */
@Slf4j
@Component
public class ConversationLogAdvisor implements BaseAdvisor {

    public static final String USER_ID_PARAM = "logUserId";
    private static final String START_NANOS = "logStartNanos";

    private final ConversationService conversationService;

    public ConversationLogAdvisor(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String conversationId = (String) request.context().get(ChatMemory.CONVERSATION_ID);
        String userId = (String) request.context().get(USER_ID_PARAM);
        UserMessage userMessage = request.prompt().getUserMessage();
        if (conversationId != null && userMessage != null) {
            conversationService.logUser(conversationId, userId, userMessage.getText());
        }
        Map<String, Object> context = new HashMap<>(request.context());
        context.put(START_NANOS, System.nanoTime());
        return request.mutate().context(context).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String conversationId = (String) response.context().get(ChatMemory.CONVERSATION_ID);
        String userId = (String) response.context().get(USER_ID_PARAM);
        ChatResponse chat = response.chatResponse();
        if (conversationId == null || chat == null || chat.getResult() == null || chat.getResult().getOutput() == null) {
            return response;
        }
        String text = chat.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            return response;
        }
        Object start = response.context().get(START_NANOS);
        long latencyMs = start instanceof Long s ? (System.nanoTime() - s) / 1_000_000 : 0;
        ChatResponseMetadata meta = chat.getMetadata();
        String model = meta == null ? null : meta.getModel();
        Usage usage = meta == null ? null : meta.getUsage();
        conversationService.logAssistant(conversationId, userId, text, model, usage, latencyMs);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> responses = Mono.just(request)
                .publishOn(getScheduler())
                .map(r -> before(r, chain))
                .flatMapMany(chain::nextStream);
        return new ChatClientMessageAggregator().aggregateChatClientResponse(responses, aggregated -> after(aggregated, chain));
    }

    @Override
    public int getOrder() {
        // MessageChatMemoryAdvisor 默认 HIGHEST_PRECEDENCE + 1000，本 Advisor 排在其后
        return Ordered.HIGHEST_PRECEDENCE + 2000;
    }
}
