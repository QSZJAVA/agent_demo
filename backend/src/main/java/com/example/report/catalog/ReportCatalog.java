package com.example.report.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.catalog.query.QueryAdapterFactory;
import com.example.report.catalog.query.ReportQueryAdapter;
import com.example.report.entity.ReportAlias;
import com.example.report.entity.ReportCodeMapping;
import com.example.report.entity.ReportDefinition;
import com.example.report.mapper.ReportAliasMapper;
import com.example.report.mapper.ReportCodeMappingMapper;
import com.example.report.mapper.ReportDefinitionMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 报表目录的本地缓存：定义、别名、旧编码映射、查询适配器、说法索引。
 * 目录变更后通过 Redis pub/sub 通知所有实例重新加载（与规则缓存同一套机制）。
 * 查询配置无效的报表记录错误并排除在可用目录之外，不影响其他报表。
 */
@Slf4j
@Component
public class ReportCatalog implements MessageListener {

    public static final String REFRESH_TOPIC = "catalog:refresh";

    private final ReportDefinitionMapper definitionMapper;
    private final ReportAliasMapper aliasMapper;
    private final ReportCodeMappingMapper mappingMapper;
    private final QueryAdapterFactory adapterFactory;
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;

    private final AtomicReference<State> state = new AtomicReference<>(State.EMPTY);

    private record State(List<CatalogEntry> entries, Map<String, CatalogEntry> byId, Map<String, String> legacyCodes,
                         TermIndex terms) {
        static final State EMPTY = new State(List.of(), Map.of(), Map.of(), TermIndex.EMPTY);
    }

    public ReportCatalog(ReportDefinitionMapper definitionMapper, ReportAliasMapper aliasMapper,
                         ReportCodeMappingMapper mappingMapper, QueryAdapterFactory adapterFactory,
                         StringRedisTemplate redis, RedisMessageListenerContainer listenerContainer) {
        this.definitionMapper = definitionMapper;
        this.aliasMapper = aliasMapper;
        this.mappingMapper = mappingMapper;
        this.adapterFactory = adapterFactory;
        this.redis = redis;
        this.listenerContainer = listenerContainer;
    }

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(REFRESH_TOPIC));
        reload();
    }

    public synchronized void reload() {
        List<ReportDefinition> definitions = definitionMapper.selectList(new LambdaQueryWrapper<ReportDefinition>()
                .orderByAsc(ReportDefinition::getSortOrder)
                .orderByAsc(ReportDefinition::getReportId));
        Map<String, List<ReportAlias>> aliases = new HashMap<>();
        aliasMapper.selectList(new LambdaQueryWrapper<ReportAlias>()
                        .orderByDesc(ReportAlias::getPriority)
                        .orderByAsc(ReportAlias::getId))
                .forEach(a -> aliases.computeIfAbsent(a.getReportId(), k -> new ArrayList<>()).add(a));
        Map<String, String> legacy = new HashMap<>();
        mappingMapper.selectList(null).forEach((ReportCodeMapping m) -> legacy.put(m.getLegacyCode(), m.getReportId()));

        List<CatalogEntry> entries = new ArrayList<>();
        int broken = 0;
        for (ReportDefinition d : definitions) {
            ReportQueryAdapter adapter = null;
            String error = null;
            try {
                adapter = adapterFactory.createForReport(d);
            } catch (RuntimeException e) {
                error = e.getMessage();
                broken++;
                log.error("报表 {}（{}）的查询配置无效，已排除在可用目录之外：{}", d.getReportId(), d.getReportName(), error);
            }
            entries.add(CatalogEntry.of(d, aliases.getOrDefault(d.getReportId(), List.of()).stream()
                    .filter(a -> java.util.Objects.equals(d.getTenantId(), a.getTenantId())).toList(), adapter, error));
        }
        Map<String, CatalogEntry> byId = new LinkedHashMap<>();
        entries.forEach(e -> byId.put(e.reportId(), e));
        TermIndex terms = TermIndex.build(entries.stream()
                .filter(e -> e.published() && e.usable())
                .map(e -> new TermIndex.ReportTerms(e.reportId(), e.reportName(), e.reportCode(),
                        e.activeAliases().stream().map(a -> new TermIndex.AliasTerm(a.alias(), a.priority())).toList()))
                .toList());
        state.set(new State(List.copyOf(entries), Map.copyOf(byId), Map.copyOf(legacy), terms));
        log.info("报表目录已加载：{} 张报表（已发布 {}，配置无效 {}）", entries.size(),
                entries.stream().filter(CatalogEntry::published).count(), broken);
    }

    /** Redis 广播是即时通知；定期从数据库核对可修复断线期间遗漏的通知。 */
    @Scheduled(fixedDelayString = "${agent.catalog-reconcile-ms:30000}")
    public void reconcile() {
        reload();
    }

    /** 全部报表（含草稿、停用），按目录顺序*/
    public List<CatalogEntry> all() {
        return state.get().entries();
    }

    public Optional<CatalogEntry> find(String reportId) {
        return reportId == null ? Optional.empty() : Optional.ofNullable(state.get().byId().get(reportId));
    }

    /** Demo 时期的 reportType → report_id */
    public Optional<String> reportIdForLegacyCode(String legacyCode) {
        return legacyCode == null ? Optional.empty()
                : Optional.ofNullable(state.get().legacyCodes().get(legacyCode.trim().toLowerCase()));
    }

    /** 已发布报表的说法索引（解析时再按用户可见范围过滤）*/
    public TermIndex terms() {
        return state.get().terms();
    }

    /** 目录变更后调用：事务提交后再广播给所有实例（含本实例）重新加载 */
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
            log.warn("报表目录刷新广播失败，仅刷新了本实例：{}", e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.debug("收到报表目录刷新广播");
        reload();
    }
}
