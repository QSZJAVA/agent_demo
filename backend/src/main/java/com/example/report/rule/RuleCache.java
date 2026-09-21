package com.example.report.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.report.ReportType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 生效规则的本地缓存。发布 / 回滚后通过 Redis pub/sub 通知所有实例重新加载。
 * 规则数量少，用内存 Map 即可；规则量大时可换 Caffeine，接口不变。
 */
@Slf4j
@Component
public class RuleCache implements MessageListener {

    public static final String REFRESH_TOPIC = "rule:refresh";

    private final DispatchRuleMapper ruleMapper;
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;

    /** key = reportType|companyCode */
    private final AtomicReference<Map<String, DispatchRule>> rules = new AtomicReference<>(Map.of());
    private volatile String fingerprint = "";

    public RuleCache(DispatchRuleMapper ruleMapper, StringRedisTemplate redis, RedisMessageListenerContainer listenerContainer) {
        this.ruleMapper = ruleMapper;
        this.redis = redis;
        this.listenerContainer = listenerContainer;
    }

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(REFRESH_TOPIC));
        reload();
    }

    /** 从数据库加载当前生效（published 且在生效期内）的规则；同一范围多条时取版本最高的 */
    public synchronized void reload() {
        LocalDateTime now = LocalDateTime.now();
        List<DispatchRule> published = ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                .eq(DispatchRule::getStatus, DispatchRule.STATUS_PUBLISHED)
                .orderByAsc(DispatchRule::getReportType)
                .orderByAsc(DispatchRule::getCompanyCode)
                .orderByDesc(DispatchRule::getVersion));
        Map<String, DispatchRule> map = new HashMap<>();
        for (DispatchRule rule : published) {
            if (rule.getEffectiveFrom() != null && rule.getEffectiveFrom().isAfter(now)) {
                continue;
            }
            if (rule.getEffectiveTo() != null && !rule.getEffectiveTo().isAfter(now)) {
                continue;
            }
            map.putIfAbsent(key(rule.getReportType(), rule.getCompanyCode()), rule);
        }
        rules.set(Map.copyOf(map));
        fingerprint = map.values().stream()
                .sorted(Comparator.comparing(DispatchRule::getId))
                .map(r -> r.getId() + ":" + r.getVersion())
                .collect(Collectors.joining(","));
        log.info("派单规则缓存已加载：{} 条，指纹 {}", map.size(), fingerprint);
    }

    /** 先找具体公司的规则，找不到再用通配 */
    public Optional<DispatchRule> find(ReportType type, String companyCode) {
        Map<String, DispatchRule> map = rules.get();
        DispatchRule rule = map.get(key(type.code(), companyCode));
        if (rule == null) {
            rule = map.get(key(type.code(), DispatchRule.ANY_COMPANY));
        }
        return Optional.ofNullable(rule);
    }

    public Map<String, DispatchRule> all() {
        return rules.get();
    }

    /** 当前生效规则的指纹：预览快照记录它，执行时比对，防止预览后规则变了 */
    public String fingerprint() {
        return fingerprint;
    }

    /** 发布 / 回滚后调用：事务提交后再广播给所有实例（含本实例）重新加载，避免监听线程读到未提交的数据 */
    public void broadcastRefresh() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doBroadcast();
                }
            });
        } else {
            doBroadcast();
        }
    }

    private void doBroadcast() {
        reload();
        try {
            redis.convertAndSend(REFRESH_TOPIC, "refresh");
        } catch (Exception e) {
            log.warn("规则刷新广播失败，仅刷新了本实例：{}", e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.debug("收到规则刷新广播");
        reload();
    }

    private static String key(String reportType, String companyCode) {
        return reportType + "|" + companyCode;
    }
}
