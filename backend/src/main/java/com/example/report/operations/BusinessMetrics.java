package com.example.report.operations;

import com.example.report.permission.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;

/**
 * 持久化业务操作耗时、版本及结果，按租户汇总；指标记录不包含原始业务事实或模型输入。
 */
@Slf4j
@Service
public class BusinessMetrics {
    private final JdbcTemplate jdbc;
    public BusinessMetrics(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void record(CurrentUser user, String operation, String report, String version, String outcome, long started) {
        // Telemetry failure cannot turn a committed dispatch/preview into a business failure.
        try {
            jdbc.update("INSERT INTO business_metric(tenant_id,operation,report_id,version,outcome,duration_ms,created_at) VALUES (?,?,?,?,?,?,NOW(3))",
                    user.tenantId(), operation, report, version, outcome, Math.max(0,(System.nanoTime()-started)/1_000_000));
        } catch (RuntimeException e) { log.warn("业务指标写入失败 operation={}", operation); }
    }
    public List<Map<String,Object>> summary(CurrentUser user, int days) {
        return jdbc.queryForList("""
                WITH samples AS (
                  SELECT operation,report_id,version,outcome,duration_ms,
                    ROW_NUMBER() OVER (PARTITION BY operation,report_id,version,outcome ORDER BY duration_ms) rn,
                    COUNT(*) OVER (PARTITION BY operation,report_id,version,outcome) n
                  FROM business_metric WHERE tenant_id=? AND created_at>=TIMESTAMPADD(DAY,?,NOW())
                ) SELECT operation,report_id,version,outcome,COUNT(*) samples,ROUND(AVG(duration_ms)) avg_ms,
                  MAX(CASE WHEN rn=CEIL(n*0.95) THEN duration_ms END) p95_ms
                  FROM samples GROUP BY operation,report_id,version,outcome
                  ORDER BY operation,report_id,version,outcome LIMIT 1000
                """, user.tenantId(), -days);
    }
    public List<Map<String,Object>> dispatch(CurrentUser user, int days) {
        // Current durable item state, so retries do not double-count business success.
        return jdbc.queryForList("""
                SELECT i.report_id,v.catalog_version,v.rule_version,i.status,COUNT(*) items
                FROM dispatch_plan p JOIN dispatch_plan_item i ON i.plan_id=p.id
                JOIN dispatch_preview v ON v.id=p.preview_id
                WHERE p.tenant_id=? AND p.created_at>=TIMESTAMPADD(DAY,?,NOW())
                GROUP BY i.report_id,v.catalog_version,v.rule_version,i.status
                ORDER BY i.report_id LIMIT 1000
                """, user.tenantId(), -days);
    }
    public Map<String,Object> overview(CurrentUser user,int days) {
        // Totals must not be computed from the capped detail groups at thousand-report scale.
        var requests=jdbc.queryForList("SELECT operation,outcome,COUNT(*) samples FROM business_metric WHERE tenant_id=? AND created_at>=TIMESTAMPADD(DAY,?,NOW()) AND operation IN ('RESOLVE','PREVIEW') GROUP BY operation,outcome",user.tenantId(),-days);
        var outcomes=jdbc.queryForList("SELECT i.status,COUNT(*) items FROM dispatch_plan p JOIN dispatch_plan_item i ON i.plan_id=p.id WHERE p.tenant_id=? AND p.created_at>=TIMESTAMPADD(DAY,?,NOW()) GROUP BY i.status",user.tenantId(),-days);
        return Map.of("requests",requests,"dispatch",outcomes);
    }
}
