package com.example.report.trace;

import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.entity.AgentMessage;
import com.example.report.entity.DispatchAudit;
import com.example.report.mapper.AgentMessageMapper;
import com.example.report.mapper.DispatchAuditMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 多实例可安全重复补写；目标表插入、会话计数与投递确认同事务提交。 */
@Slf4j
@Service
public class TraceProjector {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.report.operations.DataRetentionService retention;
    private final JdbcTemplate jdbc;
    private final DispatchAuditMapper audits;
    private final AgentMessageMapper messages;
    private final AgentProperties props;
    private final TransactionTemplate tx;

    public TraceProjector(JdbcTemplate jdbc, DispatchAuditMapper audits, AgentMessageMapper messages,
                          AgentProperties props, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.audits = audits;
        this.messages = messages;
        this.props = props;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${agent.trace-retry-ms:1000}")
    public void recover() {
        try {
            List<Long> pending = jdbc.queryForList("SELECT e.id FROM trace_event e WHERE e.delivery_status='PENDING' "
                    + "AND e.next_attempt_at<=NOW(3) AND (e.event_type<>'MESSAGE' OR NOT EXISTS "
                    + "(SELECT 1 FROM trace_event earlier WHERE earlier.tenant_id=e.tenant_id "
                    + "AND earlier.conversation_id=e.conversation_id AND earlier.event_type='MESSAGE' "
                    + "AND earlier.delivery_status='PENDING' AND earlier.id<e.id)) ORDER BY e.id LIMIT 100", Long.class);
            for (Long id : pending) {
                deliver(id);
                var events = jdbc.query("SELECT * FROM trace_event WHERE id=?", TraceJournal::map, id);
                if (!events.isEmpty() && TraceEvent.MESSAGE.equals(events.get(0).eventType())) {
                    TraceEvent event = events.get(0);
                    // 每个会话一轮最多补 50 条，避免积压后只能每秒恢复一条。
                    for (int i = 0; i < 49; i++) {
                        var next = jdbc.queryForList("SELECT id FROM trace_event WHERE tenant_id=? AND conversation_id=? "
                                        + "AND event_type='MESSAGE' AND delivery_status='PENDING' ORDER BY id LIMIT 1", Long.class,
                                event.tenantId(), event.conversationId());
                        if (next.isEmpty() || next.get(0).equals(id)) break;
                        Long ready = jdbc.queryForObject("SELECT COUNT(*) FROM trace_event WHERE id=? AND next_attempt_at<=NOW(3)", Long.class, next.get(0));
                        if (ready == null || ready == 0) break;
                        id = next.get(0);
                        deliver(id);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("追溯补写暂不可用，下轮继续处理", e);
        }
    }

    public void afterCommit(long eventId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deliver(eventId); }
            });
        } else deliver(eventId);
    }

    public void deliver(long eventId) {
        try {
            // 先在投影事务外读取不可变归属，避免在锁会话之前建立旧的一致性快照。
            // 真正投影前仍须锁定并重读PENDING事件，删除或其他实例已完成时直接退出。
            var candidates=jdbc.query("SELECT * FROM trace_event WHERE id=? AND delivery_status='PENDING'",TraceJournal::map,eventId);
            if(candidates.isEmpty())return;
            var candidate=candidates.get(0);
            tx.executeWithoutResult(status -> {
                if(TraceEvent.MESSAGE.equals(candidate.eventType())) {
                    // 与TraceJournal及预览写入统一为“会话→事件”锁顺序；先锁事件会与前台插入形成环路。
                    // 忙会话留给下轮恢复，不占着事件锁等待前台会话锁，也不计为投递失败。
                    var owners=jdbc.queryForList("SELECT id FROM agent_conversation WHERE id=? AND tenant_id=? AND user_id=? FOR UPDATE SKIP LOCKED",
                            String.class,candidate.conversationId(),candidate.tenantId(),candidate.userId());
                    if(owners.isEmpty())return;
                }
                List<TraceEvent> found = jdbc.query("SELECT * FROM trace_event WHERE id=? AND delivery_status='PENDING' FOR UPDATE SKIP LOCKED",
                        TraceJournal::map, eventId);
                if (found.isEmpty()) return;
                TraceEvent event = found.get(0);
                if (TraceEvent.MESSAGE.equals(event.eventType())) {
                    Long earlier = jdbc.queryForObject("SELECT COUNT(*) FROM trace_event WHERE tenant_id=? AND conversation_id=? "
                                    + "AND event_type='MESSAGE' AND delivery_status='PENDING' AND id<?", Long.class,
                            event.tenantId(), event.conversationId(), event.id());
                    if (earlier != null && earlier > 0) return;
                    projectMessage(event);
                } else if (TraceEvent.AUDIT.equals(event.eventType())) {
                    if (count("dispatch_audit", event.id()) == 0) {
                        DispatchAudit audit = JsonUtil.fromJson(event.payload(), DispatchAudit.class);
                        audit.setId(null);
                        audit.setEvidenceId(event.id());
                        audits.insert(audit);
                    }
                }
                jdbc.update("UPDATE trace_event SET delivery_status='DELIVERED',delivered_at=NOW(3),last_error=NULL WHERE id=?", event.id());
            });
        } catch (RuntimeException e) {
            // 单条失败回滚展示写入，可靠事件仍在；退避最多 5 分钟，不因次数耗尽丢弃。
            log.warn("追溯事件 {} 补写失败，保留事件并自动重试", eventId, e);
            try {
                tx.executeWithoutResult(status -> jdbc.update("UPDATE trace_event SET delivery_attempts=delivery_attempts+1,"
                                + "next_attempt_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(delivery_attempts,8))),NOW(3)),last_error=? "
                                + "WHERE id=? AND delivery_status='PENDING'", bounded(e.toString(), 1024), eventId));
            } catch (RuntimeException unavailable) { log.warn("追溯重试时间保存失败，事件仍待补写 id={}", eventId); }
        }
    }

    private void projectMessage(TraceEvent event) {
        if (retention != null && retention.erased(event.conversationId())) return;
        if (count("agent_message", event.id()) != 0) return;
        var envelope = JsonUtil.toMap(event.payload());
        AgentMessage message = JsonUtil.MAPPER.convertValue(envelope.get("message"), AgentMessage.class);
        message.setId(null);
        message.setEvidenceId(event.id());
        messages.insert(message);
        boolean title = Boolean.TRUE.equals(envelope.get("maybeTitle"));
        String proposed = message.getContent() == null ? "" : message.getContent().replaceAll("\\s+", " ").trim();
        int changed = jdbc.update("UPDATE agent_conversation SET message_count=message_count+1,"
                        + "last_message_at=IF(last_message_at IS NULL OR last_message_at<?,?,last_message_at),"
                        + "updated_at=GREATEST(updated_at,?),title=IF(? AND title IS NULL,?,title) WHERE id=? AND tenant_id=? AND user_id=?",
                message.getCreatedAt(), message.getCreatedAt(), message.getCreatedAt(), title,
                bounded(proposed, props.getConversation().getTitleMaxLength()), event.conversationId(), event.tenantId(), event.userId());
        if (changed != 1) throw new IllegalStateException("消息所属会话不存在，保留证据待处理");
    }

    private long count(String table, long id) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE evidence_id=?", Long.class, id);
        return value == null ? 0 : value;
    }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
