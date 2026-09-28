package com.example.report.trace;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.entity.DispatchPlanItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 创建清单时按租户、报表、规则 ID 与版本冻结规则，禁止拿新版规则冒充历史依据。 */
@Service
public class RuleEvidence {
    private final JdbcTemplate jdbc;

    public RuleEvidence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String capture(String tenantId, DispatchPlanItem item) {
        if (item.getRuleId() == null) return JsonUtil.toJson(Map.of("kind", "MANUAL", "description", "人工选择，不套用自动派单规则"));
        var rows = jdbc.queryForList("SELECT id AS ruleId,version,report_id AS reportId,company_code AS companyCode,name,expression,description "
                        + "FROM dispatch_rule WHERE tenant_id=? AND report_id=? AND id=? AND version=?",
                tenantId, item.getReportId(), item.getRuleId(), item.getRuleVersion());
        if (rows.isEmpty()) {
            rows = jdbc.queryForList("SELECT rule_id AS ruleId,version,report_id AS reportId,company_code AS companyCode,name,expression,description "
                            + "FROM dispatch_rule_history WHERE tenant_id=? AND report_id=? AND rule_id=? AND version=? ORDER BY id DESC LIMIT 1",
                    tenantId, item.getReportId(), item.getRuleId(), item.getRuleVersion());
        }
        if (rows.isEmpty()) throw new ApiException(409, "无法保存该清单的规则依据，请重新预览后再试");
        return JsonUtil.toJson(rows.get(0));
    }
}
