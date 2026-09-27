package com.example.report.dispatch;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.catalog.query.FactRow;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.common.TraceIds;
import com.example.report.config.ResourceQuotaService;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.entity.DispatchAudit;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.entity.DispatchPreview;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.Candidate;
import com.example.report.rule.DispatchCandidateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 派单执行：归属 → 状态 → 版本 → 认领（CAS，只执行一次）→ 按当前数据复核 → 逐条调派单接口 → 逐条状态与审计 → 结果卡片。
 * 重复确认同一份已执行的清单返回第一次的结果，不会再次调用派单接口。
 */
@Slf4j
@Service
public class DispatchService {

    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_MANUAL = "manual";
    private static final String RECORD_CHANGED = "预览之后记录已变化（已派单、不再满足规则或不在您的可见范围），未派单";

    private final PlanService planService;
    private final PreviewService previewService;
    private final PlanRepository plans;
    private final ReportCatalogService catalogService;
    private final DispatchCandidateService candidateService;
    private final DispatchVersionService versions;
    private final DispatchGateway gateway;
    private final AuditService auditService;
    private final ConversationService conversationService;
    private final ChatMemory chatMemory;
    private final ScheduledExecutorService executionHeartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "dispatch-execution-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ResourceQuotaService quotas;

    public DispatchService(PlanService planService, PreviewService previewService, PlanRepository plans,
                           ReportCatalogService catalogService, DispatchCandidateService candidateService,
                           DispatchVersionService versions, DispatchGateway gateway, AuditService auditService,
                           ConversationService conversationService, ChatMemory chatMemory) {
        this.planService = planService;
        this.previewService = previewService;
        this.plans = plans;
        this.catalogService = catalogService;
        this.candidateService = candidateService;
        this.versions = versions;
        this.gateway = gateway;
        this.auditService = auditService;
        this.conversationService = conversationService;
        this.chatMemory = chatMemory;
    }

    public DispatchResultPayload confirm(CurrentUser user, String planId) {
        return confirm(user, planId, TraceIds.current());
    }

    /** 确认执行一份待确认清单（前端确认按钮，或 require-confirm=false 时由工具直接调用） */
    public DispatchResultPayload confirm(CurrentUser user, String planId, String traceId) {
        try (ResourceQuotaService.Permit ignored = acquirePlanPermit(user, planId)) {
            return confirmWithPermit(user, planId, traceId);
        }
    }

    private DispatchResultPayload confirmWithPermit(CurrentUser user, String planId, String traceId) {
        PlanSnapshot snapshot = planService.getOwned(user, planId);
        DispatchPlan plan = snapshot.plan();
        if (DispatchPlan.EXECUTED.equals(plan.getStatus())) {
            return replay(snapshot);
        }
        if (!DispatchPlan.PENDING.equals(plan.getStatus())) {
            throw statusError(plan);
        }
        DispatchPreview preview = previewService.findOwned(user, plan.getPreviewId())
                .orElseThrow(() -> ApiException.notFound("待确认清单对应的预览不存在，请重新预览"));
        LocalDateTime now = LocalDateTime.now();
        // 认领前再校验一次版本，缩小“校验通过”与“开始执行”之间的窗口
        String reason = versions.verify(user, preview);
        if (reason != null) {
            previewService.expire(preview, reason, now);
            planService.expire(plan, reason, now);
            throw new ApiException("该清单已失效：" + StateReason.message(reason));
        }
        // 认领：PENDING 且未过期 → EXECUTING，并发确认只有一个请求能成功
        if (!planService.claimForExecution(user, plan, now)) {
            DispatchPlan latest = plans.find(plan.getId()).orElse(plan);
            if (DispatchPlan.EXECUTED.equals(latest.getStatus())) {
                return replay(new PlanSnapshot(latest, plans.items(latest.getId())));
            }
            if (DispatchPlan.PENDING.equals(latest.getStatus())) {
                planService.expire(latest, StateReason.TTL, now);
                throw new ApiException("该清单已失效：" + StateReason.message(StateReason.TTL));
            }
            throw statusError(latest);
        }
        DispatchResultPayload result;
        ScheduledFuture<?> heartbeat = startHeartbeat(plan.getId());
        try {
            result = execute(user, plan, preview, snapshot.items(), traceId);
        } catch (RuntimeException e) {
            // 只有仍在执行中的异常才需要核对；网关调用前的查询失败会恢复为 PENDING。
            boolean reviewRequired;
            try {
                reviewRequired = plans.transition(plan.getId(), DispatchPlan.EXECUTING, DispatchPlan.REVIEW_REQUIRED,
                        StateReason.EXECUTION_INTERRUPTED, LocalDateTime.now());
            } catch (RuntimeException persistenceError) {
                log.error("清单执行中断且状态保存失败 plan={}", plan.getId(), persistenceError);
                throw new ApiException(409, StateReason.message(StateReason.EXECUTION_INTERRUPTED));
            }
            if (reviewRequired) {
                log.error("清单执行中断，需核对 plan={}", plan.getId(), e);
                throw new ApiException(409, StateReason.message(StateReason.EXECUTION_INTERRUPTED));
            }
            throw e;
        } finally {
            heartbeat.cancel(false);
        }
        // 派单已经发生，下面的收尾失败只记日志：不能让用户看到 500 以为没派，再去重复操作
        try {
            if (plan.getConversationId() != null) {
                conversationService.logCard(plan.getConversationId(), user.userId(), "result", result, preview.getId(), plan.getId());
                // 让模型知道这份清单已经执行过（工作记忆），后续对话不会再拿它说事
                chatMemory.add(plan.getConversationId(), new AssistantMessage(memoryNote(result)));
            }
        } catch (RuntimeException e) {
            log.error("派单清单 {} 已执行（成功 {} 失败 {}），收尾记录失败", plan.getId(), result.successCount(), result.failedCount(), e);
        }
        return result;
    }

    /** 仅重试外部接口明确返回失败的条目；SUCCESS/SKIPPED/UNKNOWN 绝不重发。 */
    public DispatchResultPayload retryFailed(CurrentUser user, String planId) {
        try (ResourceQuotaService.Permit ignored = acquirePlanPermit(user, planId)) {
            return retryFailedWithPermit(user, planId);
        }
    }

    private DispatchResultPayload retryFailedWithPermit(CurrentUser user, String planId) {
        PlanSnapshot snapshot = planService.getOwned(user, planId);
        DispatchPlan plan = snapshot.plan();
        if (!DispatchPlan.EXECUTED.equals(plan.getStatus()) || plan.getFailedCount() == null || plan.getFailedCount() == 0) {
            throw new ApiException(409, "该清单没有可重试的失败记录");
        }
        List<DispatchPlanItem> retryItems = snapshot.items().stream()
                .filter(i -> DispatchPlanItem.FAILED.equals(i.getStatus())).toList();
        if (retryItems.isEmpty()) throw new ApiException(409, "失败记录需人工核对，不能自动重试");
        DispatchPreview preview = previewService.findOwned(user, plan.getPreviewId())
                .orElseThrow(() -> ApiException.notFound("原预览不存在"));
        String reason = versions.verifyForRetry(user, preview);
        if (reason != null) throw new ApiException(409, "权限、报表或规则已变化，请重新预览后处理失败记录");
        if (!plans.claimRetry(planId, LocalDateTime.now())) {
            throw new ApiException(409, "该清单正在处理或已被其他请求重试");
        }
        ScheduledFuture<?> heartbeat = startHeartbeat(planId);
        boolean attempted = false;
        try {
            Set<String> reportIds = retryItems.stream().map(DispatchPlanItem::getReportId).collect(Collectors.toSet());
            List<CatalogEntry> reports = catalogService.inCatalogOrder(reportIds);
            Map<String, CatalogEntry> byId = reports.stream().collect(Collectors.toMap(CatalogEntry::reportId, r -> r));
            Set<String> qualified = candidateService.qualifiedPlanKeys(user.tenantId(),
                    DispatchVersionService.companies(preview), reports, idsByReport(retryItems));
            if (versions.verifyForRetry(user, preview) != null) {
                plans.transition(planId, DispatchPlan.EXECUTING, DispatchPlan.EXECUTED, null, LocalDateTime.now());
                throw new ApiException(409, "规则或权限已变化，未重试任何记录");
            }
            AuditService.Context ctx = new AuditService.Context(SOURCE_AGENT, plan.getConversationId(), preview.getId(),
                    planId, preview.getRuleVersion(), preview.getPermissionVersion(), TraceIds.current());
            boolean uncertain = false;
            for (DispatchPlanItem item : retryItems) {
                Candidate c = PlanSnapshot.toCandidate(item);
                CatalogEntry report = byId.get(item.getReportId());
                if (report == null || !qualified.contains(c.key())) continue;
                String requestId = item.getExternalRequestId() == null
                        ? planId + "-" + item.getId() : item.getExternalRequestId();
                // 先留下可核对的状态。网关成功后进程崩溃时，不能让旧 FAILED 状态掩盖已发送的请求。
                item.setStatus(DispatchPlanItem.UNKNOWN);
                item.setExternalRequestId(requestId);
                item.setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + 1);
                item.setErrorCode("RESULT_UNKNOWN");
                item.setErrorMessage("重试请求结果待核对");
                item.setUpdatedAt(LocalDateTime.now());
                plans.updateItem(item);
                attempted = true;
                DispatchGateway.Outcome outcome = callGateway(user, requestId, report, c);
                String code = outcome.success() ? DispatchPlanItem.SUCCESS
                        : "RESULT_UNKNOWN".equals(outcome.errorCode()) ? DispatchPlanItem.UNKNOWN : DispatchPlanItem.FAILED;
                uncertain |= DispatchPlanItem.UNKNOWN.equals(code);
                item.setStatus(code);
                item.setErrorCode(outcome.errorCode());
                item.setErrorMessage(outcome.success() ? null : outcome.message());
                item.setUpdatedAt(LocalDateTime.now());
                plans.updateItem(item);
                recordAudit(user, ctx, c, item.getId(), requestId, code, outcome.errorCode(), outcome.message());
            }
            if (uncertain) throw new IllegalStateException("重试结果未知，需人工核对");
            List<DispatchPlanItem> latest = plans.items(planId);
            int successes = (int) latest.stream().filter(i -> DispatchPlanItem.SUCCESS.equals(i.getStatus())).count();
            if (!plans.finish(planId, successes, latest.size() - successes, LocalDateTime.now())) {
                throw new IllegalStateException("失败项重试后清单收尾失败");
            }
            DispatchResultPayload result = currentResult(new PlanSnapshot(plans.find(planId).orElse(plan), latest));
            recordUpdatedResult(user, plan, result);
            return result;
        } catch (RuntimeException e) {
            if (DispatchPlan.EXECUTING.equals(plans.find(planId).orElse(plan).getStatus())) {
                plans.transition(planId, DispatchPlan.EXECUTING,
                        attempted ? DispatchPlan.REVIEW_REQUIRED : DispatchPlan.EXECUTED,
                        attempted ? StateReason.EXECUTION_INTERRUPTED : null, LocalDateTime.now());
            }
            if (e instanceof ApiException api) throw api;
            throw new ApiException(409, StateReason.message(StateReason.EXECUTION_INTERRUPTED));
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> startHeartbeat(String planId) {
        return executionHeartbeats.scheduleAtFixedRate(() -> {
            try {
                plans.touchExecuting(planId, LocalDateTime.now());
            } catch (RuntimeException e) {
                log.warn("派单执行心跳更新失败 plan={}", planId, e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy
    public void shutdownHeartbeats() {
        executionHeartbeats.shutdownNow();
    }

    /** 查询外部幂等请求号，只有全部未知项得到确定结果后才解除待核对状态。 */
    public DispatchResultPayload reconcile(CurrentUser user, String planId) {
        try (ResourceQuotaService.Permit ignored = acquirePlanPermit(user, planId)) {
            return reconcileWithPermit(user, planId);
        }
    }

    private DispatchResultPayload reconcileWithPermit(CurrentUser user, String planId) {
        PlanSnapshot snapshot = planService.getOwned(user, planId);
        if (!DispatchPlan.REVIEW_REQUIRED.equals(snapshot.plan().getStatus())) {
            throw new ApiException(409, "该清单不需要核对");
        }
        for (DispatchPlanItem item : snapshot.items()) {
            String expectedStatus = item.getStatus();
            if (!DispatchPlanItem.UNKNOWN.equals(expectedStatus) && !DispatchPlanItem.PENDING.equals(expectedStatus)) {
                continue;
            }
            String requestId = item.getExternalRequestId() == null
                    ? planId + "-" + item.getId() : item.getExternalRequestId();
            DispatchGateway.Lookup lookup = gateway.lookup(user.tenantId(), requestId);
            if (lookup == null || lookup.status() == DispatchGateway.LookupStatus.UNKNOWN) continue;
            boolean success = lookup.status() == DispatchGateway.LookupStatus.SUCCESS;
            item.setStatus(success ? DispatchPlanItem.SUCCESS : DispatchPlanItem.FAILED);
            item.setErrorCode(success ? null : lookup.status() == DispatchGateway.LookupStatus.NOT_FOUND
                    ? "NOT_SENT" : lookup.errorCode());
            item.setErrorMessage(success ? null : lookup.status() == DispatchGateway.LookupStatus.NOT_FOUND
                    ? "外部系统确认未收到该请求，可重试" : lookup.message());
            item.setExternalRequestId(requestId);
            item.setUpdatedAt(LocalDateTime.now());
            if (!plans.resolveUnknownItem(item, expectedStatus, snapshot.plan().getExecutionVersion())) {
                throw new ApiException(409, "清单已被其他请求核对或重试，请刷新后重新核对");
            }
            DispatchPreview preview = previewService.findOwned(user, snapshot.plan().getPreviewId()).orElse(null);
            AuditService.Context ctx = new AuditService.Context("reconcile", snapshot.plan().getConversationId(),
                    snapshot.plan().getPreviewId(), planId,
                    preview == null ? null : preview.getRuleVersion(),
                    preview == null ? null : preview.getPermissionVersion(), TraceIds.current());
            recordAudit(user, ctx, PlanSnapshot.toCandidate(item), item.getId(), requestId,
                    item.getStatus(), item.getErrorCode(), "核对 " + expectedStatus + " → " + item.getStatus()
                            + (item.getErrorMessage() == null ? "" : "：" + item.getErrorMessage()));
        }
        List<DispatchPlanItem> latest = plans.items(planId);
        if (latest.stream().anyMatch(i -> DispatchPlanItem.UNKNOWN.equals(i.getStatus())
                || DispatchPlanItem.PENDING.equals(i.getStatus()))) {
            throw new ApiException(409, "部分外部请求仍无确定结果，请稍后核对；不要重复派单");
        }
        int successCount = (int) latest.stream().filter(i -> DispatchPlanItem.SUCCESS.equals(i.getStatus())).count();
        if (!plans.finishReview(planId, snapshot.plan().getExecutionVersion(), successCount,
                latest.size() - successCount, LocalDateTime.now())) {
            throw new ApiException(409, "清单核对状态已变化，请刷新");
        }
        DispatchResultPayload result = currentResult(new PlanSnapshot(plans.find(planId).orElse(snapshot.plan()), latest));
        recordUpdatedResult(user, snapshot.plan(), result);
        return result;
    }

    private ResourceQuotaService.Permit acquirePlanPermit(CurrentUser user, String planId) {
        if (quotas == null) return null;
        DispatchPlan plan = planService.findOwned(user, planId)
                .orElseThrow(() -> ApiException.notFound("待确认清单不存在"));
        DispatchPreview preview = previewService.findOwned(user, plan.getPreviewId())
                .orElseThrow(() -> ApiException.notFound("原预览不存在"));
        return quotas.acquire(user, "dispatch", DispatchVersionService.reportIds(preview));
    }

    private void recordUpdatedResult(CurrentUser user, DispatchPlan plan, DispatchResultPayload result) {
        if (plan.getConversationId() == null) return;
        try {
            conversationService.logCard(plan.getConversationId(), user.userId(), "result", result,
                    plan.getPreviewId(), plan.getId());
            chatMemory.add(plan.getConversationId(), new AssistantMessage(memoryNote(result)));
        } catch (RuntimeException e) {
            log.error("清单 {} 最新结果已落库，但会话记录写入失败", plan.getId(), e);
        }
    }

    private DispatchResultPayload currentResult(PlanSnapshot snapshot) {
        DispatchResultPayload result = replay(snapshot);
        return new DispatchResultPayload(result.planId(), result.previewId(), result.total(),
                result.successCount(), result.failedCount(), result.retryableCount(), result.success(), result.failed(), false);
    }

    /** 取消一份待确认清单；已取消、已失效的重复取消不报错 */
    public DispatchPlan cancel(CurrentUser user, String planId) {
        boolean wasPending = planService.findOwned(user, planId).map(p -> DispatchPlan.PENDING.equals(p.getStatus())).orElse(false);
        DispatchPlan plan = planService.cancel(user, planId);
        if (wasPending && DispatchPlan.CANCELLED.equals(plan.getStatus()) && plan.getConversationId() != null) {
            try {
                chatMemory.add(plan.getConversationId(), new AssistantMessage("（系统记录）用户取消了待确认的派单清单，未执行任何派单。"));
            } catch (RuntimeException e) {
                log.warn("取消清单 {} 的工作记忆写入失败", planId, e);
            }
        }
        return plan;
    }

    /** 报表页手工派单：按记录主键，只允许操作可见报表中、用户可见公司的记录 */
    public DispatchResultPayload dispatchDirect(CurrentUser user, String reportIdOrLegacyCode, List<String> recordIds) {
        CatalogEntry report = catalogService.requireVisibleByIdOrLegacyCode(user, reportIdOrLegacyCode);
        report = catalogService.requireDispatchable(user, report.reportId());
        ResourceQuotaService.Permit permit = quotas == null ? null : quotas.acquire(user, "dispatch", List.of(report.reportId()));
        try {
        List<String> ids = recordIds.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        Map<String, FactRow> rows = new LinkedHashMap<>();
        report.adapter().rowsByIds(user.tenantId(), ids).forEach(r -> rows.put(r.recordId(), r));
        List<Candidate> records = new ArrayList<>();
        for (String id : ids) {
            FactRow row = rows.get(id);
            if (row == null || !user.companies().contains(row.companyCode())) {
                throw ApiException.forbidden("记录 " + id + " 不存在或不在您的可见范围内");
            }
            records.add(DispatchCandidateService.toCandidate(report, row, null, "手工派单", null, null));
        }
        AuditService.Context ctx = new AuditService.Context(SOURCE_MANUAL, null, null, null, null,
                user.permissionVersion(), TraceIds.current());
        List<Candidate> success = new ArrayList<>();
        List<DispatchResultPayload.FailedRecord> failed = new ArrayList<>();
        for (Candidate c : records) {
            String requestId = "manual-" + JsonUtil.newId();
            DispatchGateway.Outcome outcome = callGateway(user, requestId, report, c);
            String code = outcome.success() ? DispatchAudit.OUTCOME_SUCCESS
                    : "RESULT_UNKNOWN".equals(outcome.errorCode()) ? DispatchPlanItem.UNKNOWN : DispatchAudit.OUTCOME_FAILED;
            recordAudit(user, ctx, c, null, requestId, code, outcome.errorCode(), outcome.message());
            if (outcome.success()) {
                success.add(c);
            } else {
                failed.add(failedRecord(c, code, outcome.errorCode(), outcome.message()));
            }
        }
        return new DispatchResultPayload(null, null, records.size(), success.size(), failed.size(),
                (int) failed.stream().filter(f -> DispatchPlanItem.FAILED.equals(f.outcome())).count(), success, failed, false);
        } finally {
            if (permit != null) permit.close();
        }
    }

    private DispatchResultPayload execute(CurrentUser user, DispatchPlan plan, DispatchPreview preview,
                                          List<DispatchPlanItem> items, String traceId) {
        AuditService.Context ctx = new AuditService.Context(SOURCE_AGENT, plan.getConversationId(), preview.getId(), plan.getId(),
                preview.getRuleVersion(), preview.getPermissionVersion(), traceId);
        Set<String> reportIds = items.stream().map(DispatchPlanItem::getReportId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<CatalogEntry> reports = catalogService.inCatalogOrder(reportIds);
        Map<String, CatalogEntry> reportById = reports.stream().collect(Collectors.toMap(CatalogEntry::reportId, r -> r));
        // 清单里的记录是预览时的快照，执行前按当前数据复核：记录仍未派单、仍满足当前规则、仍在预览时的公司范围内。
        // 版本一致只能说明目录、规则、权限没变，预览之后记录本身被修改（例如金额改小、已被别人派掉）时快照仍会照旧派出去
        Set<String> qualified;
        try {
            qualified = candidateService.qualifiedPlanKeys(user.tenantId(),
                    DispatchVersionService.companies(preview), reports, idsByReport(items));
        } catch (RuntimeException e) {
            // 尚未调用任何网关，恢复后可安全重试；下次确认仍会重验归属、预览及版本。
            plans.transition(plan.getId(), DispatchPlan.EXECUTING, DispatchPlan.PENDING, null, LocalDateTime.now());
            throw new ApiException(503, "执行前数据复核失败，尚未派单，请稍后重试");
        }
        String invalid = versions.verify(user, preview);
        if (invalid != null) {
            plans.transition(plan.getId(), DispatchPlan.EXECUTING, DispatchPlan.EXPIRED, invalid, LocalDateTime.now());
            throw new ApiException("该清单已失效：" + StateReason.message(invalid));
        }
        List<Candidate> success = new ArrayList<>();
        List<DispatchResultPayload.FailedRecord> failed = new ArrayList<>();
        boolean itemPersistenceFailed = false;
        boolean uncertainOutcome = false;
        for (DispatchPlanItem item : items) {
            Candidate c = PlanSnapshot.toCandidate(item);
            CatalogEntry report = reportById.get(item.getReportId());
            String requestId = plan.getId() + "-" + item.getId();
            boolean sent = report != null && qualified.contains(c.key());
            String code;
            String errorCode;
            String message;
            if (sent) {
                DispatchGateway.Outcome outcome = callGateway(user, requestId, report, c);
                code = outcome.success() ? DispatchPlanItem.SUCCESS
                        : "RESULT_UNKNOWN".equals(outcome.errorCode()) ? DispatchPlanItem.UNKNOWN : DispatchPlanItem.FAILED;
                uncertainOutcome |= DispatchPlanItem.UNKNOWN.equals(code);
                errorCode = outcome.errorCode();
                message = outcome.message();
            } else {
                code = DispatchPlanItem.SKIPPED;
                errorCode = "RECORD_CHANGED";
                message = RECORD_CHANGED;
            }
            item.setStatus(code);
            item.setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + (sent ? 1 : 0));
            item.setExternalRequestId(sent ? requestId : null);
            item.setErrorCode(errorCode);
            item.setErrorMessage(DispatchPlanItem.SUCCESS.equals(code) ? null : message);
            item.setUpdatedAt(LocalDateTime.now());
            try {
                plans.updateItem(item);
                plans.touchExecuting(plan.getId(), LocalDateTime.now());
            } catch (RuntimeException e) {
                itemPersistenceFailed = true;
                // 条目状态写失败不能中断整批：前面的记录已经派出，中断会让后面的记录静默丢失
                log.error("清单条目状态写入失败 plan={} item={} {} status={}", plan.getId(), item.getId(), c.docNo(), code, e);
            }
            recordAudit(user, ctx, c, item.getId(), sent ? requestId : null, code, errorCode, message);
            if (DispatchPlanItem.SUCCESS.equals(code)) {
                success.add(c);
            } else {
                failed.add(failedRecord(c, code, errorCode, message));
            }
        }
        LocalDateTime finished = LocalDateTime.now();
        previewService.consume(preview.getId(), finished);
        if (itemPersistenceFailed || uncertainOutcome) {
            throw new IllegalStateException(itemPersistenceFailed ? "部分派单结果未能持久化，必须核对后处理"
                    : "网关结果未知，必须按外部请求号核对后处理");
        }
        if (!plans.finish(plan.getId(), success.size(), failed.size(), finished)) {
            throw new IllegalStateException("派单清单状态收尾失败：" + plan.getId());
        }
        return new DispatchResultPayload(plan.getId(), preview.getId(), items.size(), success.size(), failed.size(),
                (int) failed.stream().filter(f -> DispatchPlanItem.FAILED.equals(f.outcome())).count(), success, failed, false);
    }

    private static Map<String, List<String>> idsByReport(List<DispatchPlanItem> items) {
        return items.stream().collect(Collectors.groupingBy(DispatchPlanItem::getReportId,
                LinkedHashMap::new, Collectors.mapping(DispatchPlanItem::getRecordId, Collectors.toList())));
    }

    private DispatchGateway.Outcome callGateway(CurrentUser user, String requestId, CatalogEntry report, Candidate c) {
        try {
            return gateway.dispatch(new DispatchGateway.DispatchRequest(user.tenantId(), requestId, report, c));
        } catch (Exception e) {
            log.warn("派单接口调用异常 {} {} {}", report.reportId(), c.docNo(), e.getMessage());
            return DispatchGateway.Outcome.fail("RESULT_UNKNOWN", "派单接口结果未知，需按请求号核对：" + e.getMessage());
        }
    }

    private void recordAudit(CurrentUser user, AuditService.Context ctx, Candidate c, Long itemId, String requestId,
                             String outcome, String errorCode, String message) {
        try {
            auditService.record(user, ctx, c, itemId, requestId, outcome, errorCode, message);
        } catch (RuntimeException e) {
            // 审计失败不能中断整批。日志里留全派单结果，便于事后补审计
            log.error("派单审计写入失败 user={} plan={} {} {}#{} outcome={} message={}", user.userId(), ctx.planId(),
                    c.reportId(), c.docNo(), c.recordId(), outcome, message, e);
        }
    }

    /** 已执行清单的结果：按条目状态还原，不调用派单接口 */
    DispatchResultPayload replay(PlanSnapshot snapshot) {
        List<Candidate> success = new ArrayList<>();
        List<DispatchResultPayload.FailedRecord> failed = new ArrayList<>();
        for (DispatchPlanItem item : snapshot.items()) {
            Candidate c = PlanSnapshot.toCandidate(item);
            if (DispatchPlanItem.SUCCESS.equals(item.getStatus())) {
                success.add(c);
            } else {
                String message = item.getErrorMessage() == null ? "执行结果未知，请联系管理员核对" : item.getErrorMessage();
                failed.add(failedRecord(c, item.getStatus(), item.getErrorCode(), message));
            }
        }
        return new DispatchResultPayload(snapshot.plan().getId(), snapshot.plan().getPreviewId(), snapshot.items().size(),
                success.size(), failed.size(), (int) snapshot.items().stream()
                        .filter(i -> DispatchPlanItem.FAILED.equals(i.getStatus())).count(), success, failed, true);
    }

    private static DispatchResultPayload.FailedRecord failedRecord(Candidate c, String outcome, String errorCode, String message) {
        return new DispatchResultPayload.FailedRecord(c.reportId(), c.reportName(), c.docNo(), c.companyCode(), outcome,
                errorCode, message);
    }

    private static ApiException statusError(DispatchPlan plan) {
        return switch (plan.getStatus()) {
            case DispatchPlan.EXECUTING -> new ApiException(409, "该清单正在执行，请稍后刷新查看结果");
            case DispatchPlan.REVIEW_REQUIRED -> new ApiException(409, StateReason.message(StateReason.EXECUTION_INTERRUPTED));
            case DispatchPlan.CANCELLED -> new ApiException("该清单已取消，如需派单请重新生成清单");
            case DispatchPlan.EXPIRED -> new ApiException("该清单已失效：" + StateReason.message(plan.getStatusReason()));
            default -> new ApiException("该清单当前状态为 " + plan.getStatus() + "，不能执行");
        };
    }

    private static String memoryNote(DispatchResultPayload r) {
        StringBuilder sb = new StringBuilder("（系统记录）派单清单已执行：成功 ")
                .append(r.successCount()).append(" 条，失败 ").append(r.failedCount()).append(" 条。");
        if (!r.success().isEmpty()) {
            sb.append("成功单据：");
            r.success().forEach(c -> sb.append(c.docNo()).append(' '));
        }
        if (!r.failed().isEmpty()) {
            sb.append("失败单据：");
            r.failed().forEach(f -> sb.append(f.docNo()).append('(').append(f.message()).append(") "));
        }
        return sb.toString().trim();
    }
}
