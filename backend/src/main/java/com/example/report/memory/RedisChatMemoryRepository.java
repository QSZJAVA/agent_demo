package com.example.report.memory;

import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.entity.AgentMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 模型工作记忆（Redis，TTL）。只服务模型上下文，不是持久记录。
 * Redis 未命中时从 MySQL 对话日志回灌最近 N 条用户 / 助手消息。
 * Spring AI 1.1.x 官方没有 Redis 记忆仓库，接口只有四个方法。
 */
@Slf4j
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final TypeReference<List<StoredMessage>> LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ConversationService conversationService;
    private final AgentProperties props;

    public RedisChatMemoryRepository(StringRedisTemplate redis, ConversationService conversationService, AgentProperties props) {
        this.redis = redis;
        this.conversationService = conversationService;
        this.props = props;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redis.keys("agent:memory:*");
        if (keys == null) {
            return List.of();
        }
        return keys.stream().map(k -> k.substring("agent:memory:".length())).toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String json = redis.opsForValue().get(key(conversationId));
        if (json != null) {
            return toMessages(parse(json));
        }
        // 工作记忆过期：从对话日志回灌
        List<AgentMessage> recent = conversationService.recentTextMessages(conversationId, props.getMemory().getWindowSize());
        if (recent.isEmpty()) {
            return new ArrayList<>();
        }
        List<StoredMessage> stored = recent.stream()
                .map(m -> new StoredMessage(AgentMessage.ROLE_USER.equals(m.getRole()) ? "user" : "assistant", m.getContent()))
                .toList();
        write(conversationId, stored);
        log.debug("工作记忆已从对话日志回灌：conversation={} 条数={}", conversationId, stored.size());
        return toMessages(stored);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<StoredMessage> stored = new ArrayList<>();
        for (Message m : messages) {
            MessageType type = m.getMessageType();
            if (type == MessageType.USER || type == MessageType.ASSISTANT || type == MessageType.SYSTEM) {
                if (m.getText() != null && !m.getText().isBlank()) {
                    stored.add(new StoredMessage(type.getValue(), m.getText()));
                }
            }
        }
        write(conversationId, stored);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(key(conversationId));
    }

    private void write(String conversationId, List<StoredMessage> stored) {
        redis.opsForValue().set(key(conversationId), JsonUtil.toJson(stored), Duration.ofMinutes(props.getMemory().getTtlMinutes()));
    }

    private static List<StoredMessage> parse(String json) {
        try {
            return JsonUtil.MAPPER.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Message> toMessages(List<StoredMessage> stored) {
        List<Message> messages = new ArrayList<>(stored.size());
        for (StoredMessage s : stored) {
            switch (s.role()) {
                case "user" -> messages.add(new UserMessage(s.text()));
                case "system" -> messages.add(new SystemMessage(s.text()));
                default -> messages.add(new AssistantMessage(s.text()));
            }
        }
        return messages;
    }

    private static String key(String conversationId) {
        return "agent:memory:" + conversationId;
    }

    public record StoredMessage(String role, String text) {
    }
}
