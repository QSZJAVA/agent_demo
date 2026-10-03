package com.example.report.operations;

import com.example.report.permission.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/**
 * 记录运维操作的操作者、资源、原因和结果；所有内容保存前脱敏，事务归属由调用方决定。
 */
@Service
public class OperationsAudit {
    private final JdbcTemplate jdbc;
    public OperationsAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void record(CurrentUser actor, String action, String resource, String outcome, String reason) {
        jdbc.update("INSERT INTO operations_audit(tenant_id,actor_id,action,resource_id,outcome,reason,created_at) VALUES (?,?,?,?,?,?,NOW(3))",
                actor.tenantId(), actor.userId(), action, resource, outcome, SensitiveData.text(reason));
    }
    public List<Map<String,Object>> list(CurrentUser actor, long before) {
        return jdbc.queryForList("SELECT * FROM operations_audit WHERE tenant_id=? AND id<? ORDER BY id DESC LIMIT 100",
                actor.tenantId(), before <= 0 ? Long.MAX_VALUE : before);
    }
}
