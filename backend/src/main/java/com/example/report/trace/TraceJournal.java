package com.example.report.trace;

import com.example.report.common.Digests;
import com.example.report.common.JsonUtil;
import com.example.report.entity.AgentMessage;
import com.example.report.entity.DispatchAudit;
import com.example.report.entity.DispatchPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 先同步提交可靠证据，再异步同步展示表；事件写入失败必须向业务调用方传播。 */
@Service
public class TraceJournal {
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;

    public TraceJournal(JdbcTemplate jdbc, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public long audit(DispatchAudit audit) {
        String key = "audit:" + audit.getPlanId() + ":" + audit.getPlanItemId() + ":"
                + audit.getExecutionVersion() + ":" + audit.getAttemptCount() + ":" + audit.getPhase();
        return append(key, audit.getTenantId(), audit.getUserId(), audit.getConversationId(), audit.getPreviewId(),
                audit.getPlanId(), TraceEvent.AUDIT, audit, audit.getCreatedAt());
    }

    public long message(AgentMessage message, boolean maybeTitle, String key) {
        return tx.execute(status -> {
            // 同会话消息的提交顺序确定；补写器也按此顺序投影，重试不会把早期消息插到后面。
            var owners = jdbc.queryForList("SELECT id FROM agent_conversation WHERE id=? AND tenant_id=? AND user_id=? FOR UPDATE",
                    message.getConversationId(), message.getTenantId(), message.getUserId());
            if (owners.isEmpty()) throw new IllegalStateException("消息所属会话不存在");
            return append(key, message.getTenantId(), message.getUserId(), message.getConversationId(),
                    message.getPreviewId(), message.getPlanId(), TraceEvent.MESSAGE,
                    Map.of("message", message, "maybeTitle", maybeTitle), message.getCreatedAt());
        });
    }

    public void plan(DispatchPlan plan, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("plan", plan);
        append("plan:" + plan.getId() + ":" + JsonUtil.newId(), plan.getTenantId(),
                plan.getConfirmedBy() == null ? plan.getUserId() : plan.getConfirmedBy(),
                plan.getConversationId(), plan.getPreviewId(), plan.getId(), TraceEvent.PLAN, payload, LocalDateTime.now());
    }

    private long append(String key, String tenantId, String userId, String conversationId, String previewId,
                        String planId, String type, Object payload, LocalDateTime occurredAt) {
        if (tenantId == null || userId == null) throw new IllegalArgumentException("追溯事件必须绑定租户和操作者");
        String eventKey = Digests.sha256(tenantId + "|" + key);
        DispatchAudit audit = payload instanceof DispatchAudit a ? a : null;
        jdbc.update("INSERT INTO trace_event (event_key,tenant_id,user_id,conversation_id,preview_id,plan_id,event_type,payload,created_at,delivery_status,"
                        + "plan_item_id,execution_version,attempt_count,phase,outcome) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE event_key=VALUES(event_key)",
                eventKey, tenantId, userId, conversationId, previewId, planId, type, JsonUtil.toJson(payload),
                occurredAt, TraceEvent.PLAN.equals(type) ? "DELIVERED" : "PENDING",
                audit == null ? null : audit.getPlanItemId(), audit == null ? null : audit.getExecutionVersion(),
                audit == null ? null : audit.getAttemptCount(), audit == null ? null : audit.getPhase(),
                audit == null ? null : audit.getOutcome());
        return jdbc.queryForObject("SELECT id FROM trace_event WHERE event_key=?", Long.class, eventKey);
    }

    public static TraceEvent map(ResultSet rs, int row) throws SQLException {
        return new TraceEvent(rs.getLong("id"), rs.getString("event_key"), rs.getString("tenant_id"),
                rs.getString("user_id"), rs.getString("conversation_id"), rs.getString("preview_id"),
                rs.getString("plan_id"), rs.getString("event_type"), rs.getString("payload"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("delivery_status"), rs.getInt("delivery_attempts"));
    }
}
