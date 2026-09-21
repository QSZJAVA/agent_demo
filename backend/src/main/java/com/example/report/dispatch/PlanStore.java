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
 * 待确认派单清单存储（Redis，带 TTL，一次性消费）
 */
@Component
public class PlanStore {

    private final StringRedisTemplate redis;
    private final AgentProperties props;

    public PlanStore(StringRedisTemplate redis, AgentProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public DispatchPlan save(Snapshot snapshot, List<Candidate> records, List<String> excluded) {
        DispatchPlan plan = new DispatchPlan(JsonUtil.newId(), snapshot.userId(), snapshot.conversationId(),
                snapshot.id(), snapshot.rulesFingerprint(), LocalDateTime.now(), records, excluded);
        Duration ttl = Duration.ofMinutes(props.getPlan().getTtlMinutes());
        // 同一会话只保留最近一份待确认清单：先作废上一份，避免用户点旧卡片把已排除的记录派出去
        supersedeLatest(plan.userId(), plan.conversationId());
        redis.opsForValue().set(key(plan.userId(), plan.id()), JsonUtil.toJson(plan), ttl);
        if (plan.conversationId() != null) {
            redis.opsForValue().set(latestKey(plan.userId(), plan.conversationId()), plan.id(), ttl);
        }
        return plan;
    }

    /** 作废本会话上一份待确认清单；对应卡片前端会置为已过期，点了也不会执行 */
    public void supersedeLatest(String userId, String conversationId) {
        if (conversationId == null) {
            return;
        }
        String previous = redis.opsForValue().get(latestKey(userId, conversationId));
        if (previous != null && !previous.isBlank()) {
            redis.delete(key(userId, previous));
        }
        redis.delete(latestKey(userId, conversationId));
    }

    public Optional<DispatchPlan> load(String userId, String planId) {
        if (planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(key(userId, planId));
        return json == null ? Optional.empty() : Optional.of(JsonUtil.fromJson(json, DispatchPlan.class));
    }

    public void delete(String userId, String planId) {
        redis.delete(key(userId, planId));
    }

    private static String key(String userId, String planId) {
        return "agent:plan:" + userId + ":" + planId;
    }

    private static String latestKey(String userId, String conversationId) {
        return "agent:plan:latest:" + userId + ":" + conversationId;
    }
}
