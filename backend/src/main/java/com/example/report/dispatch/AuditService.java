package com.example.report.dispatch;

import com.example.report.entity.DispatchAudit;
import com.example.report.mapper.DispatchAuditMapper;
import com.example.report.rule.Candidate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 派单审计：每条记录一行
 */
@Service
public class AuditService {

    private final DispatchAuditMapper auditMapper;

    public AuditService(DispatchAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    public void record(String userId, String source, String conversationId, String previewId, String planId,
                       Candidate c, DispatchGateway.Outcome outcome) {
        DispatchAudit audit = new DispatchAudit();
        audit.setUserId(userId);
        audit.setSource(source);
        audit.setConversationId(conversationId);
        audit.setPreviewId(previewId);
        audit.setPlanId(planId);
        audit.setReportType(c.reportType());
        audit.setRecordId(c.recordId());
        audit.setDocNo(c.docNo());
        audit.setCompanyCode(c.companyCode());
        audit.setAmount(c.amount());
        audit.setRuleName(c.ruleName());
        audit.setRuleVersion(c.ruleVersion());
        audit.setSuccess(outcome.success());
        audit.setMessage(outcome.message());
        audit.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(audit);
    }
}
