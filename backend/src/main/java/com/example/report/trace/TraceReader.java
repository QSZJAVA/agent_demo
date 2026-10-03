package com.example.report.trace;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.entity.DispatchPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只接受已通过清单及会话权限校验的对象；每一条 SQL 仍强制租户条件。 */
@Service
public class TraceReader {
    private final JdbcTemplate jdbc;

    public TraceReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 按当前授权范围查询的有界分页结果。
     * @param records 当前对象的有界业务记录集合
     * @param total 授权范围内统计总数，不能用当前页长度代替
     * @param nextCursor 下一页查询游标；没有后续数据时为空
     */
    public record Page(List<Map<String, Object>> records, long total, Long nextCursor) { }

    public Page page(DispatchPlan plan, String section, long afterId, int size) {
        if (afterId < 0 || size < 1 || size > 100) throw new ApiException("追溯分页参数无效");
        String table;
        String where;
        Object[] args;
        switch (section) {
            case "events" -> { table = "trace_event"; where = eventScope(); args = scopeArgs(plan); }
            case "audits" -> { table = "dispatch_audit"; where = "tenant_id=? AND plan_id=?"; args = new Object[]{plan.getTenantId(), plan.getId()}; }
            case "messages" -> { table = "agent_message"; where = "tenant_id=? AND conversation_id=?"; args = new Object[]{plan.getTenantId(), plan.getConversationId()}; }
            case "items" -> {
                table = "dispatch_plan_item";
                where = "plan_id=? AND EXISTS (SELECT 1 FROM dispatch_plan p WHERE p.id=plan_id AND p.tenant_id=?)";
                args = new Object[]{plan.getId(), plan.getTenantId()};
            }
            default -> throw new ApiException("不支持的追溯分页类型");
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class, args);
        var parameters = new ArrayList<>(java.util.Arrays.asList(args));
        parameters.add(afterId);
        parameters.add(size + 1);
        var rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE (" + where + ") AND id>? ORDER BY id LIMIT ?", parameters.toArray());
        boolean more = rows.size() > size;
        List<Map<String, Object>> records = rows.stream().limit(size).map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(row);
            // 故障细节留服务日志；业务页面只显示补写状态和尝试次数。
            result.remove("last_error");
            for (String field : List.of("payload", "rule_snapshot")) {
                Object json = result.get(field);
                if (json != null) {
                    String text = json instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : json.toString();
                    result.put(field, JsonUtil.toMap(text));
                }
            }
            return result;
        }).toList();
        Long next = more ? ((Number) records.get(records.size() - 1).get("id")).longValue() : null;
        return new Page(records, count == null ? 0 : count, next);
    }

    public Map<String, Object> integrity(DispatchPlan plan) {
        long pending = count("SELECT COUNT(*) FROM trace_event WHERE (" + eventScope() + ") AND delivery_status='PENDING'", scopeArgs(plan));
        long retrying = count("SELECT COUNT(*) FROM trace_event WHERE (" + eventScope() + ") AND delivery_status='PENDING' AND delivery_attempts>0", scopeArgs(plan));
        long missingRules = count("SELECT COUNT(*) FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id "
                + "WHERE p.tenant_id=? AND p.id=? AND i.rule_id IS NOT NULL AND i.rule_snapshot IS NULL", plan.getTenantId(), plan.getId());
        long missingAudits = count("SELECT COUNT(*) FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id "
                + "WHERE p.tenant_id=? AND p.id=? AND i.status<>'PENDING' AND NOT EXISTS "
                + "(SELECT 1 FROM trace_event e WHERE e.tenant_id=p.tenant_id AND e.plan_id=p.id AND e.plan_item_id=i.id "
                + "AND e.event_type='AUDIT' AND e.attempt_count=COALESCE(i.attempt_count,0) AND e.outcome=i.status)", plan.getTenantId(), plan.getId());
        long unresolved = count("SELECT COUNT(*) FROM dispatch_plan_item WHERE plan_id=? AND status IN ('UNKNOWN','PENDING')", plan.getId());
        boolean legacy = plan.getEvidenceVersion() == null || plan.getEvidenceVersion() < 1;
        List<String> warnings = new ArrayList<>();
        if (legacy) warnings.add("升级前的历史清单：可查看现存记录，无法证明当时未丢失事件。");
        if (missingRules > 0) warnings.add("有 " + missingRules + " 条记录缺少可核实的历史规则原文。");
        if (missingAudits > 0) warnings.add("有 " + missingAudits + " 条记录的当前状态缺少对应可靠审计事件，请人工核查。");
        if (pending > 0) warnings.add("有 " + pending + " 条可靠事件待同步到审计或历史展示表，原始事件已保存，可在事件页查看。");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", missingRules > 0 || missingAudits > 0 ? "INCOMPLETE" : legacy ? "LEGACY" : pending > 0 ? "SYNCING" : "COMPLETE");
        result.put("pendingCount", pending);
        result.put("retryingCount", retrying);
        result.put("missingRules", missingRules);
        result.put("missingAudits", missingAudits);
        result.put("unresolvedItems", unresolved);
        result.put("warnings", warnings);
        return result;
    }

    /** 只调整补写时间，绝不执行派单、重置业务状态或生成虚构的历史审计。 */
    public int retry(DispatchPlan plan) {
        return jdbc.update("UPDATE trace_event SET next_attempt_at=NOW(3) WHERE (" + eventScope() + ") AND delivery_status='PENDING'", scopeArgs(plan));
    }

    private static String eventScope() {
        return "tenant_id=? AND (plan_id=? OR (event_type='MESSAGE' AND conversation_id=?))";
    }

    private static Object[] scopeArgs(DispatchPlan plan) {
        return new Object[]{plan.getTenantId(), plan.getId(), plan.getConversationId()};
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }
}
