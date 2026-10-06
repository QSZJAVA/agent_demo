package com.example.report.dispatch;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.ApiException;
import com.example.report.common.TraceIds;
import com.example.report.config.ResourceQuotaService;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.entity.DispatchPreview;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.Candidate;
import com.example.report.rule.DispatchCandidateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

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
    private final TransactionOperations tx;
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
                           ConversationService conversationService, TransactionOperations tx) {
        this.planService = planService;
        this.previewService = previewService;
        this.plans = plans;
        this.catalogService = catalogService;
        this.candidateService = candidateService;
        this.versions = versions;
        this.gateway = gateway;
        this.auditService = auditService;
        this.conversationService = conversationService;
        this.tx = tx;
    }

    public DispatchResultPayload confirm(CurrentUser user, String planId) {
        return confirm(user, planId, TraceIds.current());
    }

    /** 执行用户已确认的待确认清单；业务入口必须先验证确认与当前执行版本 */
    public DispatchResultPayload confirm(CurrentUser user, String planId, String traceId) {
        return confirm(user,planId,traceId,null);
    }

    /**
     * 确认执行时取得并发配额；已结束清单回放结果。
     * @param expectedVersion 异步任务提交时的执行版本；为空表示同步入口，非空时必须与认领前版本一致
     * @return 逐条派单结果，包含失败、可重试数量及回放标记
     */
    public DispatchResultPayload confirm(CurrentUser user, String planId, String traceId, Long expectedVersion) {
        try (ResourceQuotaService.Permit permit = acquirePlanPermit(user, planId)) {
            return confirmWithPermit(user, planId, traceId, permit,expectedVersion);
        }
    }

    private DispatchResultPayload confirmWithPermit(CurrentUser user, String planId, String traceId, ResourceQuotaService.Permit permit) {
        return confirmWithPermit(user,planId,traceId,permit,null);
    }

    private DispatchResultPayload confirmWithPermit(CurrentUser user, String planId, String traceId, ResourceQuotaService.Permit permit,Long expectedVersion) {
        ResourceQuotaService.check(permit);
        PlanSnapshot snapshot = planService.getOwned(user, planId);
        DispatchPlan plan = snapshot.plan();
        requireExpectedVersion(plan,expectedVersion);
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
        var claimedVersion = planService.claimForExecution(user, plan, now);
        if (claimedVersion.isEmpty()) {
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
        long executionVersion = claimedVersion.orElseThrow();
        DispatchResultPayload result;
        ScheduledFuture<?> heartbeat = startHeartbeat(plan.getId(), executionVersion);
        try {
            result = execute(user, plan, preview, snapshot.items(), traceId, executionVersion, permit);
        } catch (RuntimeException e) {
            // 只有仍在执行中的异常才需要核对；网关调用前的查询失败会恢复为 PENDING。
            boolean reviewRequired;
            try {
                reviewRequired = plans.transitionExecution(plan.getId(), executionVersion, DispatchPlan.REVIEW_REQUIRED,
                        StateReason.EXECUTION_INTERRUPTED, LocalDateTime.now());
            } catch (RuntimeException persistenceError) {
                log.error("清单执行中断且状态保存失败 plan={}", plan.getId(), persistenceError);
                throw new ApiException(409, StateReason.message(StateReason.EXECUTION_INTERRUPTED));
            }
            if (reviewRequired) {
                log.error("清单执行中断，需核对 plan={}", plan.getId(), e);
                throw new ApiException(409, StateReason.message(StateReason.EXECUTION_INTERRUPTED));
            }
            DispatchPlan current = plans.find(plan.getId()).orElse(plan);
            if (!Objects.equals(current.getExecutionVersion(), executionVersion)
                    || DispatchPlan.REVIEW_REQUIRED.equals(current.getStatus())) {
                throw new ApiException(409, "清单执行权已失效，请刷新并核对结果");
            }
            throw e;
        } finally {
            heartbeat.cancel(false);
        }
        return result;
    }

    /** 仅重试外部接口明确返回失败的条目；SUCCESS/SKIPPED/UNKNOWN 绝不重发。 */
    public DispatchResultPayload retryFailed(CurrentUser user, String planId) {
        return retryFailed(user,planId,null);
    }

    /**
     * 仅重发业务接口已明确返回 FAILED 的条目；UNKNOWN 先核对，SUCCESS 与 SKIPPED 不重发。异步调用须绑定提交时的清单版本。
     */
    public DispatchResultPayload retryFailed(CurrentUser user, String planId, Long expectedVersion) {
        try (ResourceQuotaService.Permit permit = acquirePlanPermit(user, planId)) {
            return retryFailedWithPermit(user, planId, permit,expectedVersion);
        }
    }

    private DispatchResultPayload retryFailedWithPermit(CurrentUser user, String planId, ResourceQuotaService.Permit permit,Long expectedVersion) {
        ResourceQuotaService.check(permit);
        PlanSnapshot snapshot = planService.getOwned(user, planId);
        DispatchPlan plan = snapshot.plan();
        requireExpectedVersion(plan,expectedVersion);
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
        var claimedVersion = expectedVersion==null ? plans.claimRetry(planId, LocalDateTime.now())
                : plans.claimRetry(planId,expectedVersion,LocalDateTime.now());
        if (claimedVersion.isEmpty()) {
            throw new ApiException(409, "该清单正在处理或已被其他请求重试");
        }
        long executionVersion = claimedVersion.orElseThrow();
        ScheduledFuture<?> heartbeat = startHeartbeat(planId, executionVersion);
        boolean attempted = false;
        try {
            Set<String> reportIds = retryItems.stream().map(DispatchPlanItem::getReportId).collect(Collectors.toSet());
            List<CatalogEntry> reports = catalogService.inCatalogOrder(reportIds).stream().map(r -> r.forUser(user)).toList();
            Map<String, CatalogEntry> byId = reports.stream().collect(Collectors.toMap(CatalogEntry::reportId, r -> r));
            Set<String> qualified = qualifiedKeys(user, preview, reports, retryItems);
            if (versions.verifyForRetry(user, preview) != null) {
                plans.transitionExecution(planId, executionVersion, DispatchPlan.EXECUTED, null, LocalDateTime.now());
                throw new ApiException(409, "规则或权限已变化，未重试任何记录");
            }
            AuditService.Context ctx = new AuditService.Context(SOURCE_MANUAL.equals(preview.getSource()) ? SOURCE_MANUAL : SOURCE_AGENT, plan.getConversationId(), preview.getId(),
                    planId, preview.getRuleVersion(), preview.getPermissionVersion(), TraceIds.current());
            boolean uncertain = false;
            for (DispatchPlanItem item : retryItems) {
                ResourceQuotaService.check(permit);
                requireExecution(planId, executionVersion);
                Candidate c = PlanSnapshot.toCandidate(item);
                CatalogEntry report = byId.get(item.getReportId());
                if (report == null || !qualified.contains(c.key())) continue;
                String requestId = item.getExternalRequestId() == null
                        ? planId + "-" + item.getId() : item.getExternalRequestId();
                // 先留下可核对的状态。网关成功后进程崩溃时，不能让旧 FAILED 状态掩盖已发送的请求。
                item.setStatus(DispatchPlanItem.UNKNOWN);
                item.setExternalRequestId(requestId);
                item.setAttemptCount((item.getAttemptCount()) + 1);
                item.setErrorCode("RESULT_UNKNOWN");
                item.setErrorMessage("重试请求结果待核对");
                item.setUpdatedAt(LocalDateTime.now());
                persistItem(user, ctx, item, executionVersion, "INTENT");
                attempted = true;
                DispatchGateway.Outcome outcome = plans.withExecutionRight(planId, executionVersion,
                        () -> {
                            ResourceQuotaService.check(permit);
                            return callGateway(user, requestId, report, c, !SOURCE_MANUAL.equals(preview.getSource()), executionVersion);
                });
                String code = outcome.success() ? DispatchPlanItem.SUCCESS
                        : "RESULT_UNKNOWN".equals(outcome.errorCode()) ? DispatchPlanItem.UNKNOWN : DispatchPlanItem.FAILED;
                uncertain |= DispatchPlanItem.UNKNOWN.equals(code);
                item.setStatus(code);
                item.setErrorCode(outcome.errorCode());
                item.setErrorMessage(outcome.success() ? null : outcome.message());
                item.setUpdatedAt(LocalDateTime.now());
                persistItem(user, ctx, item, executionVersion, "RESULT");
            }
            if (uncertain) throw new IllegalStateException("重试结果未知，需人工核对");
            List<DispatchPlanItem> latest = plans.items(planId);
            int successes = (int) latest.stream().filter(i -> DispatchPlanItem.SUCCESS.equals(i.getStatus())).count();
            DispatchResultPayload result = currentResult(new PlanSnapshot(plan, latest));
            tx.executeWithoutResult(status -> {
                if (!plans.finish(planId, executionVersion, successes, latest.size() - successes, LocalDateTime.now())) {
                    throw new IllegalStateException("失败项重试后清单收尾失败");
                }
                persistResultCard(user, plan, result);
            });

            return result;
        } catch (RuntimeException e) {
            if (plans.isExecuting(planId, executionVersion)) {
                plans.transitionExecution(planId, executionVersion,
                        attempted ? DispatchPlan.REVIEW_REQUIRED : DispatchPlan.EXECUTED,
                        attempted ? StateReason.EXECUTION_INTERRUPTED : null, LocalDateTime.now());
            }
            if (e instanceof ApiException api) throw api;
            throw new ApiException(409, StateReason.message(StateReason.EXECUTION_INTERRUPTED));
        } finally {
            heartbeat.cancel(false);
        }
    }

    /**
     * 按认领版本续写清单心跳，避免另一个执行轮次被旧定时器误续租；执行结束必须取消定时器。
     */
    private ScheduledFuture<?> startHeartbeat(String planId, long executionVersion) {
        return executionHeartbeats.scheduleAtFixedRate(() -> {
            try {
                plans.touchExecuting(planId, executionVersion, LocalDateTime.now());
            } catch (RuntimeException e) {
                log.warn("派单执行心跳更新失败 plan={}", planId, e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 每次外部写入前验证清单仍处于 EXECUTING 且版本属于本轮；旧轮次即使查询过事实也不能继续发送。
     */
    private void requireExecution(String planId, long executionVersion) {
        if(Thread.currentThread().isInterrupted()) throw new ApiException(409,"任务已中断，请刷新并核对持久结果");
        if (!plans.isExecuting(planId, executionVersion)) {
            throw new ApiException(409, "清单执行权已失效，请刷新并核对结果");
        }
    }

    /**
     * 异步排队期间清单可能已核对或重试；版本不同拒绝这条旧命令，不将旧请求升级到最新轮次。
     */
    private static void requireExpectedVersion(DispatchPlan plan,Long expectedVersion) {
        if(expectedVersion!=null && !Objects.equals(expectedVersion,plan.getExecutionVersion()))
            throw new ApiException(409,"任务提交后清单执行版本已变化，请刷新后重新处理");
    }

    @jakarta.annotation.PreDestroy
    public void shutdownHeartbeats() {
        executionHeartbeats.shutdownNow();
    }

    /** 查询外部幂等请求号，只有全部未知项得到确定结果后才解除待核对状态。 */
    public DispatchResultPayload reconcile(CurrentUser user, String planId) {
        try (ResourceQuotaService.Permit permit = acquirePlanPermit(user, planId)) {
            return reconcileWithPermit(user, user, planId, permit);
        }
    }

    /** Read-only lookup delegated by an administrator; this path never submits or retries mutations.  * 管理员代核对使用真实操作者的幂等请求号；管理员身份用于服务端范围授权和审计，不能冒充原用户执行新的写入。*/
    public DispatchResultPayload reconcileForOperator(CurrentUser actor,String operatorId,String planId) {
        com.example.report.operations.OperationsPolicy.requireAdmin(actor);
        CurrentUser scope = new CurrentUser(actor.tenantId(),operatorId,actor.displayName(),actor.companies(),actor.permissions(),true);
        // Ownership and the administrator's current data grants are checked before acquiring quota or lookup.
        planService.getOwned(scope,planId);
        try(ResourceQuotaService.Permit permit = acquirePlanPermit(scope,planId)) {
            return reconcileWithPermit(scope,actor,planId,permit);
        }
    }

    private DispatchResultPayload reconcileWithPermit(CurrentUser user, CurrentUser actor, String planId, ResourceQuotaService.Permit permit) {
        ResourceQuotaService.check(permit);
        PlanSnapshot snapshot = planService.getOwned(user, planId);
        if (!DispatchPlan.REVIEW_REQUIRED.equals(snapshot.plan().getStatus())) {
            throw new ApiException(409, "该清单不需要核对");
        }
        for (DispatchPlanItem item : snapshot.items()) {
            ResourceQuotaService.check(permit);
            String expectedStatus = item.getStatus();
            if (!DispatchPlanItem.UNKNOWN.equals(expectedStatus) && !DispatchPlanItem.PENDING.equals(expectedStatus)) {
                continue;
            }
            String requestId = item.getExternalRequestId() == null
                    ? planId + "-" + item.getId() : item.getExternalRequestId();
            DispatchGateway.Lookup lookup = gateway instanceof AuthenticatedDispatchGateway authenticated
                    ? (actor == user ? authenticated.lookup(user, requestId)
                    : authenticated.lookupForOperator(actor,user.userId(),requestId)) : gateway.lookup(user.tenantId(), requestId);
            if (lookup == null || lookup.status() == DispatchGateway.LookupStatus.UNKNOWN) continue;
            boolean success = lookup.status() == DispatchGateway.LookupStatus.SUCCESS;
            item.setStatus(success ? DispatchPlanItem.SUCCESS : DispatchPlanItem.FAILED);
            item.setErrorCode(success ? null : lookup.status() == DispatchGateway.LookupStatus.NOT_FOUND
                    ? "NOT_SENT" : lookup.errorCode());
            item.setErrorMessage(success ? null : lookup.status() == DispatchGateway.LookupStatus.NOT_FOUND
                    ? "外部系统确认未收到该请求，可重试" : lookup.message());
            item.setExternalRequestId(requestId);
            item.setUpdatedAt(LocalDateTime.now());
            DispatchPreview preview = previewService.findOwned(user, snapshot.plan().getPreviewId()).orElse(null);
            AuditService.Context ctx = new AuditService.Context("reconcile", snapshot.plan().getConversationId(),
                    snapshot.plan().getPreviewId(), planId,
                    preview == null ? null : preview.getRuleVersion(),
                    preview == null ? null : preview.getPermissionVersion(), TraceIds.current());
            tx.executeWithoutResult(status -> {
                if (!plans.resolveUnknownItem(item, expectedStatus, snapshot.plan().getExecutionVersion())) {
                    throw new ApiException(409, "清单已被其他请求核对或重试，请刷新后重新核对");
                }
                auditService.record(actor, ctx.forItem(item, snapshot.plan().getExecutionVersion(), "RECONCILE"),
                        PlanSnapshot.toCandidate(item), item.getId(), requestId, item.getStatus(), item.getErrorCode(),
                        "核对 " + expectedStatus + " → " + item.getStatus()
                                + (item.getErrorMessage() == null ? "" : "：" + item.getErrorMessage()));
            });
        }
        List<DispatchPlanItem> latest = plans.items(planId);
        if (latest.stream().anyMatch(i -> DispatchPlanItem.UNKNOWN.equals(i.getStatus())
                || DispatchPlanItem.PENDING.equals(i.getStatus()))) {
            throw new ApiException(409, "部分外部请求仍无确定结果，请稍后核对；不要重复派单");
        }
        int successCount = (int) latest.stream().filter(i -> DispatchPlanItem.SUCCESS.equals(i.getStatus())).count();
        DispatchResultPayload result = currentResult(new PlanSnapshot(snapshot.plan(), latest));
        tx.executeWithoutResult(status -> {
            if (!plans.finishReview(planId, snapshot.plan().getExecutionVersion(), successCount,
                    latest.size() - successCount, LocalDateTime.now())) {
                throw new ApiException(409, "清单核对状态已变化，请刷新");
            }
            persistResultCard(user, snapshot.plan(), result);
        });

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


    private DispatchResultPayload currentResult(PlanSnapshot snapshot) {
        DispatchResultPayload result = replay(snapshot);
        return new DispatchResultPayload(result.planId(), result.previewId(), result.total(),
                result.successCount(), result.failedCount(), result.retryableCount(), result.success(), result.failed(), false);
    }

    /** 取消一份待确认清单；已取消、已失效的重复取消不报错 */
    public DispatchPlan cancel(CurrentUser user, String planId) {
        return planService.cancel(user, planId);
    }

    /**
     * 报表页手工派单：按记录主键，只允许操作可见报表中、用户可见公司的记录
     * @param planId 派单清单标识，关联服务端持久化清单
     * @param docNo 来源业务单据号，可空时表示来源未提供
     * @param status 当前业务状态，以所属状态机为准
     * @param retryableCount 业务接口已明确失败且允许重试的条目数；未知结果不能直接重发
     * @param outcome 逐条业务结果：SUCCESS、FAILED、SKIPPED或UNKNOWN
     * @param message 可展示的操作摘要或失败原因，禁止包含凭据
     */
    public record ManualPlan(String planId, String docNo, String status, int retryableCount, String outcome, String message) { }
    /**
     * 人工记录派单的汇总与逐条清单结果。
     * @param total 授权范围内统计总数，不能用当前页长度代替
     * @param successCount 成功派单的记录数
     * @param failedCount 非成功记录统计，具体结果见失败条目或待核对状态
     * @param reviewCount 需要结果核对的记录数
     * @param plans 清单或以清单标识为键的权威状态集合
     */
    public record ManualResult(int total, int successCount, int failedCount, int reviewCount, List<ManualPlan> plans) { }

    /**
     * 人工选择入口为每条记录创建可追溯清单再执行；保留用户指定范围并校验当前业务授权，最多50条。返回每条清单及待核对数量。
     */
    public ManualResult dispatchDirect(CurrentUser user, String reportId, List<String> recordIds) {
        CatalogEntry report = catalogService.requireVisible(user, reportId);
        report = catalogService.requireDispatchable(user, report.reportId());
        List<String> ids = recordIds.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (ids.isEmpty() || ids.size() > 50) throw new ApiException("每次请选择 1～50 条记录");
        try (ResourceQuotaService.Permit permit = quotas == null ? null
                : quotas.acquire(user, "dispatch", List.of(report.reportId()))) {
            List<PlanSnapshot> prepared = new ArrayList<>();
            // 先持久化全部清单，任一准备失败时本次尚未调用网关。
            for (String id : ids) {
                ResourceQuotaService.check(permit);
                prepared.add(planService.manual(user, report, id));
            }
            List<ManualPlan> results = new ArrayList<>();
            int success = 0, failed = 0, review = 0;
            for (PlanSnapshot snapshot : prepared) {
                String planId = snapshot.plan().getId();
                if (DispatchPlan.PENDING.equals(snapshot.plan().getStatus())) {
                    try { confirmWithPermit(user, planId, TraceIds.current(), permit); }
                    catch (ApiException e) {
                        // 执行结果以持久化状态为准，保留清单编号供刷新、核对或重新确认。
                        log.warn("手工清单 {} 尚未完成：{}", planId, e.getMessage());
                    }
                }
                var current = planService.getOwned(user, planId);
                success += current.items().stream().filter(i -> DispatchPlanItem.SUCCESS.equals(i.getStatus())).count();
                if (DispatchPlan.REVIEW_REQUIRED.equals(current.plan().getStatus())
                        || DispatchPlan.EXECUTING.equals(current.plan().getStatus())) review++;
                else failed += current.items().stream().filter(i -> !DispatchPlanItem.SUCCESS.equals(i.getStatus())).count();
                results.add(manualView(current));
            }
            return new ManualResult(ids.size(), success, failed, review, results);
        }
    }

    private ManualPlan manualView(PlanSnapshot snapshot) {
        return new ManualPlan(snapshot.plan().getId(), snapshot.items().get(0).getDocNo(), snapshot.plan().getStatus(),
                (int) snapshot.items().stream().filter(i -> DispatchPlanItem.FAILED.equals(i.getStatus())).count(),
                snapshot.items().get(0).getStatus(), snapshot.items().get(0).getErrorMessage());
    }

    public List<ManualPlan> manualPlans(CurrentUser user, String reportCode, int page) {
        CatalogEntry report = catalogService.requireVisible(user, reportCode);
        if (page < 1 || page > 100000) throw new ApiException("页码无效");
        return plans.manualPlans(user.tenantId(), user.userId(), report.reportId(), (page - 1) * 50, 50)
                .stream().map(p -> manualView(planService.getOwned(user, p.getId()))).toList();
    }

    private Set<String> qualifiedKeys(CurrentUser user, DispatchPreview preview, List<CatalogEntry> reports,
                                       List<DispatchPlanItem> items) {
        if (!SOURCE_MANUAL.equals(preview.getSource())) return candidateService.qualifiedPlanKeys(user.tenantId(),
                DispatchVersionService.companies(preview), reports, idsByReport(items));
        Set<String> keys = new LinkedHashSet<>();
        Map<String, List<String>> ids = idsByReport(items);
        for (CatalogEntry report : reports) {
            report.adapter().pendingRowsByIds(user.tenantId(), ids.get(report.reportId())).stream()
                    .filter(row -> user.companies().contains(row.companyCode())
                            && DispatchVersionService.companies(preview).contains(row.companyCode()))
                    .forEach(row -> keys.add(report.reportId() + ":" + row.recordId()));
        }
        return keys;
    }

    /**
     * 按当前权限、目录、规则和来源记录复核后逐条执行。发送前持久化 UNKNOWN，发送后仅保存本轮结果；连接中断保留未知状态等待核对，避免重复写入。
     */
    private DispatchResultPayload execute(CurrentUser user, DispatchPlan plan, DispatchPreview preview,
                                          List<DispatchPlanItem> items, String traceId, long executionVersion,
                                          ResourceQuotaService.Permit permit) {
        AuditService.Context ctx = new AuditService.Context(SOURCE_MANUAL.equals(preview.getSource()) ? SOURCE_MANUAL : SOURCE_AGENT, plan.getConversationId(), preview.getId(), plan.getId(),
                preview.getRuleVersion(), preview.getPermissionVersion(), traceId);
        Set<String> reportIds = items.stream().map(DispatchPlanItem::getReportId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<CatalogEntry> reports = catalogService.inCatalogOrder(reportIds).stream().map(r -> r.forUser(user)).toList();
        Map<String, CatalogEntry> reportById = reports.stream().collect(Collectors.toMap(CatalogEntry::reportId, r -> r));
        // 清单里的记录是预览时的快照，执行前按当前数据复核：记录仍未派单、仍满足当前规则、仍在预览时的公司范围内。
        // 版本一致只能说明目录、规则、权限没变，预览之后记录本身被修改（例如金额改小、已被别人派掉）时快照仍会照旧派出去
        Set<String> qualified;
        try {
            qualified = qualifiedKeys(user, preview, reports, items);
            ResourceQuotaService.check(permit);
        } catch (RuntimeException e) {
            // 尚未调用任何网关，恢复后可安全重试；下次确认仍会重验归属、预览及版本。
            plans.transitionExecution(plan.getId(), executionVersion, DispatchPlan.PENDING, null, LocalDateTime.now());
            throw new ApiException(503, "执行前数据复核失败，尚未派单，请稍后重试");
        }
        String invalid = versions.verify(user, preview);
        if (invalid != null) {
            plans.transitionExecution(plan.getId(), executionVersion, DispatchPlan.EXPIRED, invalid, LocalDateTime.now());
            throw new ApiException("该清单已失效：" + StateReason.message(invalid));
        }
        List<Candidate> success = new ArrayList<>();
        List<DispatchResultPayload.FailedRecord> failed = new ArrayList<>();
        boolean itemPersistenceFailed = false;
        boolean uncertainOutcome = false;
        for (DispatchPlanItem item : items) {
            ResourceQuotaService.check(permit);
            requireExecution(plan.getId(), executionVersion);
            Candidate c = PlanSnapshot.toCandidate(item);
            CatalogEntry report = reportById.get(item.getReportId());
            String requestId = plan.getId() + "-" + item.getId();
            boolean sent = report != null && qualified.contains(c.key());
            String code;
            String errorCode;
            String message;
            if (sent) {
                // Persist the request number before sending; recovery can then reconcile an interrupted send.
                item.setStatus(DispatchPlanItem.UNKNOWN);
                item.setExternalRequestId(requestId);
                item.setAttemptCount((item.getAttemptCount()) + 1);
                item.setErrorCode("RESULT_UNKNOWN");
                item.setErrorMessage("派单请求结果待核对");
                item.setUpdatedAt(LocalDateTime.now());
                try {
                    persistItem(user, ctx, item, executionVersion, "INTENT");
                } catch (RuntimeException unavailable) {
                    if (success.isEmpty() && failed.isEmpty()) {
                        plans.transitionExecution(plan.getId(), executionVersion, DispatchPlan.PENDING, null, LocalDateTime.now());
                        throw new ApiException(503, "派单证据保存失败，尚未发送任何请求，请稍后重试");
                    }
                    throw unavailable;
                }
                DispatchGateway.Outcome outcome = plans.withExecutionRight(plan.getId(), executionVersion,
                        () -> {
                            ResourceQuotaService.check(permit);
                            return callGateway(user, requestId, report, c, !SOURCE_MANUAL.equals(preview.getSource()), executionVersion);
                });
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
            item.setExternalRequestId(sent ? requestId : null);
            item.setErrorCode(errorCode);
            item.setErrorMessage(DispatchPlanItem.SUCCESS.equals(code) ? null : message);
            item.setUpdatedAt(LocalDateTime.now());
            try {
                persistItem(user, ctx, item, executionVersion, "RESULT");
                plans.touchExecuting(plan.getId(), executionVersion, LocalDateTime.now());
            } catch (RuntimeException e) {
                if (!plans.isExecuting(plan.getId(), executionVersion)) throw e;
                itemPersistenceFailed = true;
                // 条目状态写失败不能中断整批：前面的记录已经派出，中断会让后面的记录静默丢失
                log.error("清单条目状态写入失败 plan={} item={} {} status={}", plan.getId(), item.getId(), c.docNo(), code, e);
            }
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
        DispatchResultPayload result = new DispatchResultPayload(plan.getId(), preview.getId(), items.size(), success.size(), failed.size(),
                (int) failed.stream().filter(f -> DispatchPlanItem.FAILED.equals(f.outcome())).count(), success, failed, false);
        tx.executeWithoutResult(status -> {
            if (!plans.finish(plan.getId(), executionVersion, success.size(), failed.size(), finished)) {
                throw new IllegalStateException("派单清单状态收尾失败：" + plan.getId());
            }
            persistResultCard(user, plan, result);
        });
        return result;
    }

    private static Map<String, List<String>> idsByReport(List<DispatchPlanItem> items) {
        return items.stream().collect(Collectors.groupingBy(DispatchPlanItem::getReportId,
                LinkedHashMap::new, Collectors.mapping(DispatchPlanItem::getRecordId, Collectors.toList())));
    }

    /**
     * 调用网关时携带认领瞬间冻结的执行版本；该调用无自动重试，传输异常不能当作业务明确失败。
     */
    private DispatchGateway.Outcome callGateway(CurrentUser user, String requestId, CatalogEntry report, Candidate c,
                                               boolean enforceRules, long executionVersion) {
        try {
            var request = new DispatchGateway.DispatchRequest(user.tenantId(), requestId, report, c, enforceRules, executionVersion);
            return gateway instanceof AuthenticatedDispatchGateway authenticated ? authenticated.dispatch(user, request) : gateway.dispatch(request);
        } catch (Exception e) {
            log.warn("派单接口调用异常 {} {} {}", report.reportId(), c.docNo(), e.getMessage());
            return DispatchGateway.Outcome.fail("RESULT_UNKNOWN", "派单接口结果未知，需按请求号核对：" + e.getMessage());
        }
    }

    /**
     * 条目状态与审计证据在同一短事务保存，并绑定执行版本；数据库失败不能留下只有展示结果而没有权威状态的记录。
     */
    private void persistItem(CurrentUser user, AuditService.Context ctx, DispatchPlanItem item,
                             long executionVersion, String phase) {
        tx.executeWithoutResult(status -> {
            plans.updateItem(item, executionVersion);
            auditService.record(user, ctx.forItem(item, executionVersion, phase), PlanSnapshot.toCandidate(item),
                    item.getId(), item.getExternalRequestId(), item.getStatus(), item.getErrorCode(),
                    DispatchPlanItem.SUCCESS.equals(item.getStatus()) ? "派单成功" : item.getErrorMessage());
        });
    }

    private void persistResultCard(CurrentUser user, DispatchPlan plan, DispatchResultPayload result) {
        if (plan.getConversationId() != null) {
            conversationService.logCard(plan.getConversationId(), user.userId(), "result", result,
                    plan.getPreviewId(), plan.getId());
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

}
