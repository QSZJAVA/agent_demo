package com.example.report.dispatch;

import com.example.report.catalog.query.DispatchStatusWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 派单接口的模拟实现：通过报表查询适配器把记录标记为已派单。真实系统替换为调用现有派单接口。
 * 不再按报表类型分支：任何配置了派单状态列的标准报表都能直接派单。
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="business.remote.enabled",havingValue="false",matchIfMissing=true)
public class MockDispatchGateway implements DispatchGateway {

    private final JdbcTemplate jdbc;
    private final com.example.report.rule.RuleCache rules;
    private final com.example.report.rule.RuleEngine engine;

    public MockDispatchGateway(JdbcTemplate jdbc, com.example.report.rule.RuleCache rules,
                               com.example.report.rule.RuleEngine engine) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.engine = engine;
    }

    @Override
    @Transactional
    public Outcome dispatch(DispatchRequest request) {
        Lookup prior = lookup(request.tenantId(), request.externalRequestId());
        if (prior.status() == LookupStatus.SUCCESS) return Outcome.ok();
        // 明确失败允许使用同一请求号重试；成功仍按请求号去重。
        if (!(request.report().adapter() instanceof DispatchStatusWriter writer)) {
            return Outcome.fail("NOT_SUPPORTED", "该报表未配置派单状态回写，无法派单");
        }
        boolean success = writer.markDispatchedGuarded(request.tenantId(), request.record().recordId(),
                request.record().companyCode(), LocalDateTime.now(), row -> {
                    if (!java.util.Objects.equals(row.companyCode(), request.record().companyCode())) return false;
                    if (!request.enforceRules()) return true;
                    return rules.find(request.tenantId(), request.report().reportId(), row.companyCode())
                            .filter(rule -> java.util.Objects.equals(rule.getId(), request.record().ruleId())
                                    && java.util.Objects.equals(rule.getVersion(), request.record().ruleVersion()))
                            .map(rule -> engine.matches(rule.getExpression(), row.facts())).orElse(false);
        });
        Outcome outcome = success ? Outcome.ok() : Outcome.fail("RECORD_CHANGED", "记录已变化、已派单或不再满足派单条件");
        if (prior.status() == LookupStatus.FAILED) {
            jdbc.update("UPDATE dispatch_gateway_request SET status=?,error_code=?,message=? "
                            + "WHERE tenant_id=? AND request_id=? AND status='FAILED'",
                    success ? "SUCCESS" : "FAILED", outcome.errorCode(), outcome.message(),
                    request.tenantId(), request.externalRequestId());
        } else {
            jdbc.update("INSERT INTO dispatch_gateway_request (tenant_id,request_id,report_id,record_id,status,error_code,message,created_at) "
                            + "VALUES (?,?,?,?,?,?,?,NOW())", request.tenantId(), request.externalRequestId(),
                    request.report().reportId(), request.record().recordId(), success ? "SUCCESS" : "FAILED",
                    outcome.errorCode(), outcome.message());
        }
        if (!success) return outcome;
        log.info("[模拟派单接口] {} {} {} 金额 {} 派单成功 requestId={}", request.report().reportName(),
                request.record().companyCode(), request.record().docNo(), request.record().amount(), request.externalRequestId());
        return outcome;
    }

    @Override
    public Lookup lookup(String tenantId, String externalRequestId) {
        return jdbc.query("SELECT status,error_code,message FROM dispatch_gateway_request WHERE tenant_id=? AND request_id=?",
                (rs, row) -> new Lookup("SUCCESS".equals(rs.getString("status")) ? LookupStatus.SUCCESS : LookupStatus.FAILED,
                        rs.getString("error_code"), rs.getString("message")), tenantId, externalRequestId)
                .stream().findFirst().orElse(new Lookup(LookupStatus.NOT_FOUND, null, "外部请求号不存在"));
    }
}
