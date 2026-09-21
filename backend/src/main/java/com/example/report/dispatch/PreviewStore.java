package com.example.report.dispatch;

import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.rule.Candidate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 预览快照存储（Redis，带 TTL）。key 含用户 ID，别人的快照拿不到。
 * 同时记录"本会话最近一次预览"，让模型不必回传 previewId。
 */
@Component
public class PreviewStore {

    private final StringRedisTemplate redis;
    private final AgentProperties props;

    public PreviewStore(StringRedisTemplate redis, AgentProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public Snapshot save(String userId, String conversationId, List<Candidate> candidates, String rulesFingerprint,
                         List<String> reportTypes) {
        Snapshot snapshot = new Snapshot(JsonUtil.newId(), userId, conversationId, rulesFingerprint, LocalDateTime.now(), candidates,
                reportTypes == null ? List.of() : List.copyOf(reportTypes));
        Duration ttl = Duration.ofMinutes(props.getPreview().getTtlMinutes());
        redis.opsForValue().set(key(userId, snapshot.id()), JsonUtil.toJson(snapshot), ttl);
        if (conversationId != null) {
            String previous = redis.opsForValue().get(latestKey(userId, conversationId));
            redis.opsForValue().set(latestKey(userId, conversationId), snapshot.id(), ttl);
            // 历史卡片仍保留在对话日志中，但旧快照不能再用于派单。
            delete(userId, previous);
        }
        return snapshot;
    }

    public Optional<Snapshot> load(String userId, String previewId) {
        if (previewId == null || previewId.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(key(userId, previewId));
        return json == null ? Optional.empty() : Optional.of(JsonUtil.fromJson(json, Snapshot.class));
    }

    /** 本会话最近一次预览的快照 */
    public Optional<Snapshot> loadLatest(String userId, String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        String previewId = redis.opsForValue().get(latestKey(userId, conversationId));
        return previewId == null ? Optional.empty() : load(userId, previewId);
    }

    public void delete(String userId, String previewId) {
        if (previewId != null) {
            redis.delete(key(userId, previewId));
        }
    }

    private static String key(String userId, String previewId) {
        return "agent:preview:" + userId + ":" + previewId;
    }

    private static String latestKey(String userId, String conversationId) {
        return "agent:preview:latest:" + userId + ":" + conversationId;
    }
}
