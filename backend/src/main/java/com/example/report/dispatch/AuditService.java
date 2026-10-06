package com.example.report.dispatch;

import com.example.report.entity.DispatchAudit;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.trace.TraceJournal;
import com.example.report.trace.TraceProjector;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.Candidate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 派单审计：每条记录每次派单一行，字段足以单独追溯完整链路（P0-09）
 */
@Service
public class AuditService {

    private final TraceJournal journal;
    private final TraceProjector projector;

    public AuditService(TraceJournal journal, TraceProjector projector) {
        this.journal = journal;
        this.projector = projector;
    }

    /**
     * 一次执行共用的上下文
     *
     * @param ruleFingerprint   预览时范围内生效规则的指纹
     * @param permissionVersion 预览时用户的权限版本
     * @param source 当前业务入口或解析来源标识
     * @param conversationId 用户所属会话标识；无会话的直接接口调用可为空
     * @param previewId 预览标识，选择和建单必须绑定此快照
     * @param planId 派单清单标识，关联服务端持久化清单
     * @param traceId 请求链路标识，供日志和证据关联
     * @param executionVersion 认领时冻结的清单执行轮次，旧轮次不能发送或回写
     * @param attemptCount 条目已准备发送的次数
     * @param phase 证据产生时的业务阶段
     * @param ruleSnapshot 执行依据的规则JSON快照；历史缺失证据允许为空
     */
    public record Context(String source, String conversationId, String previewId, String planId,
                          String ruleFingerprint, String permissionVersion, String traceId,
                          long executionVersion, int attemptCount, String phase, String ruleSnapshot) {
        public Context(String source, String conversationId, String previewId, String planId,
                       String ruleFingerprint, String permissionVersion, String traceId) {
            this(source, conversationId, previewId, planId, ruleFingerprint, permissionVersion, traceId, 0, 0, null, null);
        }

        public Context forItem(DispatchPlanItem item, long version, String eventPhase) {
            return new Context(source, conversationId, previewId, planId, ruleFingerprint, permissionVersion, traceId,
                    version, item.getAttemptCount(), eventPhase, item.getRuleSnapshot());
        }
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
        audit.setExecutionVersion(ctx.executionVersion());
        audit.setAttemptCount(ctx.attemptCount());
        audit.setPhase(ctx.phase());
        audit.setRuleSnapshot(ctx.ruleSnapshot());
        audit.setCreatedAt(LocalDateTime.now());
        if (ctx.phase() == null) throw new IllegalArgumentException("审计必须标明发生阶段");
        // journal 失败向外抛出，调用方同事务的条目状态一起回滚；投影失败则由持久化队列补写。
        long eventId = journal.audit(audit);
        projector.afterCommit(eventId);
    }
}
