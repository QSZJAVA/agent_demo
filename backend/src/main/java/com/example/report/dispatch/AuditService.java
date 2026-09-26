package com.example.report.dispatch;

import com.example.report.entity.DispatchAudit;
import com.example.report.mapper.DispatchAuditMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.Candidate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 派单审计：每条记录每次派单一行，字段足以单独追溯完整链路（P0-09）
 */
@Service
public class AuditService {

    private final DispatchAuditMapper auditMapper;

    public AuditService(DispatchAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /**
     * 一次执行共用的上下文
     *
     * @param ruleFingerprint   预览时范围内生效规则的指纹
     * @param permissionVersion 预览时用户的权限版本
     */
    public record Context(String source, String conversationId, String previewId, String planId,
                          String ruleFingerprint, String permissionVersion, String traceId) {
    }

    /**
     * @param outcome SUCCESS / FAILED / SKIPPED
     */
    public void record(CurrentUser user, Context ctx, Candidate c, Long planItemId, String externalRequestId,
                       String outcome, String errorCode, String message) {
        DispatchAudit audit = new DispatchAudit();
        audit.setTenantId(user.tenantId());
        audit.setUserId(user.userId());
        audit.setSource(ctx.source());
        audit.setConversationId(ctx.conversationId());
        audit.setPreviewId(ctx.previewId());
        audit.setPlanId(ctx.planId());
        audit.setPlanItemId(planItemId);
        audit.setReportId(c.reportId());
        audit.setReportName(c.reportName());
        audit.setRecordId(c.recordId());
        audit.setDocNo(c.docNo());
        audit.setCompanyCode(c.companyCode());
        audit.setAmount(c.amount());
        audit.setRuleId(c.ruleId());
        audit.setRuleName(c.ruleName());
        audit.setRuleVersion(c.ruleVersion());
        audit.setCatalogVersion(c.catalogVersion());
        audit.setRuleFingerprint(ctx.ruleFingerprint());
        audit.setPermissionVersion(ctx.permissionVersion());
        audit.setSuccess(DispatchAudit.OUTCOME_SUCCESS.equals(outcome));
        audit.setOutcome(outcome);
        audit.setErrorCode(errorCode);
        audit.setExternalRequestId(externalRequestId);
        audit.setMessage(message == null || message.length() <= 1024 ? message : message.substring(0, 1024));
        audit.setTraceId(ctx.traceId());
        audit.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(audit);
    }
}
