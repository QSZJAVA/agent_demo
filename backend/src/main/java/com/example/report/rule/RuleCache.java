package com.example.report.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.common.Digests;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchRuleMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 生效规则的本地缓存，按 report_id + 公司组织。发布 / 回滚后通过 Redis pub/sub 通知所有实例重新加载。
 * 规则数量少，用内存 Map 即可；规则量大时可换 Caffeine，接口不变。
 */
@Slf4j
@Component
public class RuleCache implements MessageListener {

    public static final String REFRESH_TOPIC = "rule:refresh";

    private final DispatchRuleMapper ruleMapper;
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;
    private final Clock clock;

    /** key = reportId|companyCode */
    private final AtomicReference<Map<String, DispatchRule>> rules = new AtomicReference<>(Map.of());
    private volatile String fingerprint = "";
    /** 已发布规则中最近一个尚未到达的生效开始 / 结束时间；到点后需要重新加载，否则按时间生效或失效的规则不会改变指纹 */
    private volatile LocalDateTime nextBoundary;

    @org.springframework.beans.factory.annotation.Autowired
    public RuleCache(DispatchRuleMapper ruleMapper, StringRedisTemplate redis, RedisMessageListenerContainer listenerContainer) {
        this(ruleMapper, redis, listenerContainer, Clock.systemDefaultZone());
    }

    RuleCache(DispatchRuleMapper ruleMapper, StringRedisTemplate redis, RedisMessageListenerContainer listenerContainer, Clock clock) {
        this.ruleMapper = ruleMapper;
        this.redis = redis;
        this.listenerContainer = listenerContainer;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(REFRESH_TOPIC));
        reload();
    }

    /** 从数据库加载当前生效（published 且在生效期内）的规则；同一范围多条时取版本最高的 */
    public synchronized void reload() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<DispatchRule> published = ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                .eq(DispatchRule::getStatus, DispatchRule.STATUS_PUBLISHED)
                .orderByAsc(DispatchRule::getReportId)
                .orderByAsc(DispatchRule::getCompanyCode)
                .orderByDesc(DispatchRule::getVersion));
        Map<String, DispatchRule> map = new HashMap<>();
        LocalDateTime boundary = null;
        for (DispatchRule rule : published) {
            boundary = earliestAfter(boundary, rule.getEffectiveFrom(), now);
            boundary = earliestAfter(boundary, rule.getEffectiveTo(), now);
            if (rule.getEffectiveFrom() != null && rule.getEffectiveFrom().isAfter(now)) {
                continue;
            }
            if (rule.getEffectiveTo() != null && !rule.getEffectiveTo().isAfter(now)) {
                continue;
            }
            map.putIfAbsent(key(rule.getTenantId(), rule.getReportId(), rule.getCompanyCode()), rule);
        }
        // 先换规则、再换指纹：读方先读指纹再求值，最坏只是带着旧指纹被拒绝一次，不会出现"新指纹 + 旧规则结果"
        rules.set(Map.copyOf(map));
        nextBoundary = boundary;
        fingerprint = map.values().stream()
                .sorted(Comparator.comparing(DispatchRule::getId))
                .map(r -> r.getId() + ":" + r.getVersion())
                .collect(Collectors.joining(","));
        log.info("派单规则缓存已加载：{} 条，指纹 {}", map.size(), fingerprint);
    }

    /**
     * 规则按生效时间自动开始 / 结束时没有发布动作，不会触发广播；每个实例定时检查，过了边界就本地重新加载。
     * 指纹随之变化，边界前生成的预览和清单在下一次校验时被拒绝。
     */
    @Scheduled(fixedDelayString = "${agent.rule-boundary-check-ms:15000}")
    public synchronized void reloadIfBoundaryPassed() {
        LocalDateTime boundary = nextBoundary;
        if (boundary != null && !boundary.isAfter(LocalDateTime.now(clock))) {
            log.info("规则生效时间边界 {} 已到，重新加载规则缓存", boundary);
            reload();
        }
    }

    private static LocalDateTime earliestAfter(LocalDateTime current, LocalDateTime candidate, LocalDateTime now) {
        if (candidate == null || !candidate.isAfter(now)) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    /** 先找具体公司的规则，找不到再用通配 */
    public Optional<DispatchRule> find(String tenantId, String reportId, String companyCode) {
        reloadIfBoundaryPassed();
        return find(rules.get(), tenantId, reportId, companyCode);
    }

    private Optional<DispatchRule> find(Map<String, DispatchRule> map, String tenantId, String reportId, String companyCode) {
        DispatchRule rule = map.get(key(tenantId, reportId, companyCode));
        if (rule == null) {
            rule = map.get(key(tenantId, reportId, DispatchRule.ANY_COMPANY));
        }
        return Optional.ofNullable(rule);
    }

    public Map<String, DispatchRule> all() {
        return rules.get();
    }

    /** 全部生效规则的指纹（日志用） */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * 指定报表 x 公司范围内实际生效的规则指纹：每个 (报表, 公司) 用哪条规则的哪个版本。
     * 范围外的规则变化（别的报表、别的公司）不影响它；范围内新增公司专属规则、修改通配规则、停用规则都会改变它。
     * 预览快照记录它，生成清单与确认执行时重新计算比对。
     */
    public String fingerprint(String tenantId, Collection<String> reportIds, Collection<String> companies) {
        reloadIfBoundaryPassed();
        Map<String, DispatchRule> snapshot = rules.get();
        StringBuilder sb = new StringBuilder("rules|").append(tenantId);
        for (String reportId : new TreeSet<>(reportIds)) {
            for (String company : new TreeSet<>(companies)) {
                sb.append('|').append(reportId).append('@').append(company).append('=')
                        .append(find(snapshot, tenantId, reportId, company).map(r -> r.getId() + ":" + r.getVersion()).orElse("-"));
            }
        }
        return Digests.sha256(sb.toString());
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

    private static String key(String tenantId, String reportId, String companyCode) {
        return tenantId + "|" + reportId + "|" + companyCode;
    }
}
