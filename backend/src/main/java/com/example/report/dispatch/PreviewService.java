package com.example.report.dispatch;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.MatchType;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.catalog.ReportRef;
import com.example.report.catalog.ResolveResult;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.config.ResourceQuotaService;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.dispatch.store.PreviewRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.rule.Candidate;
import com.example.report.rule.DispatchCandidateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 预览：解析报表 → 计算范围 → 记录版本 → 按规则求值 → 落库并作废同会话的旧预览与待确认清单。
 * 状态读取时顺带做懒惰校验：超过有效期、目录 / 规则 / 权限版本变化的预览立即置为 EXPIRED。
 */
@Slf4j
@Service
public class PreviewService {

    public static final String SCOPE_REPLACE = "replace";
    public static final String SCOPE_APPEND = "append";
    public static final String SCOPE_REMOVE = "remove";

    private static final int LABEL_MAX = 512;
    private static final int DOC_NO_MAX = 128;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.report.operations.BusinessMetrics metrics;
    private final ReportCatalogService catalogService;
    private final DispatchCandidateService candidateService;
    private final DispatchVersionService versions;
    private final PreviewRepository previews;
    private final PlanRepository plans;
    private final AgentProperties props;
    private final TransactionOperations tx;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ResourceQuotaService quotas;

    public PreviewService(ReportCatalogService catalogService, DispatchCandidateService candidateService,
                          DispatchVersionService versions, PreviewRepository previews, PlanRepository plans,
                          AgentProperties props, TransactionOperations tx) {
        this.catalogService = catalogService;
        this.candidateService = candidateService;
        this.versions = versions;
        this.previews = previews;
        this.plans = plans;
        this.props = props;
        this.tx = tx;
    }

    public PreviewOutcome preview(CurrentUser user, String conversationId, PreviewCommand command) {
        return preview(user, conversationId, command, scanned -> { }, id -> { });
    }

    /** 手工派单保留人工选定范围，不套用自动派单规则；仍绑定权限和目录版本。调用方负责事务。 */
    PreviewSnapshot manualPreview(CurrentUser user, CatalogEntry report, String recordId) {
        var row = report.adapter().rowsByIds(user.tenantId(), List.of(recordId)).stream()
                .filter(r -> recordId.equals(r.recordId())).findFirst()
                .orElseThrow(() -> ApiException.forbidden("记录不存在或不在您的可见范围内"));
        if (!user.companies().contains(row.companyCode())) throw ApiException.forbidden("记录不在您的可见范围内");
        var records = List.of(DispatchCandidateService.toCandidate(report, row, null, "手工派单", null, null));
        var reports = List.of(report);
        var companies = Set.of(row.companyCode());
        var command = new PreviewCommand(null, "manual", null, List.of(report.reportId()), null, null, null);
        var resolution = new ResolveResult(MatchType.EXACT, null, List.of(report.ref()), null, null, null, null, false);
        var p = newPreview(user, null, command, resolution, new Scope(Set.of(report.reportId()), false),
                reports, companies, versions.stamp(user, reports, companies), SCOPE_REPLACE, records, LocalDateTime.now());
        var items = List.of(toItem(p.getId(), 0, records.get(0)));
        previews.insert(p, items);
        return new PreviewSnapshot(p, items);
    }

    public long beginRequest(String conversationId) {
        return previews.beginRequest(conversationId);
    }

    public void deleteAbandonedBuilding(String previewId, LocalDateTime cutoff) {
        previews.deleteBuildingBefore(previewId, cutoff);
    }

    public PreviewOutcome preview(CurrentUser user, String conversationId, PreviewCommand command,
                                  java.util.function.IntConsumer progress) {
        return preview(user, conversationId, command, progress, id -> { });
    }

    public PreviewOutcome preview(CurrentUser user, String conversationId, PreviewCommand command,
                                  java.util.function.IntConsumer progress,
                                  java.util.function.Consumer<String> activation) {
        return preview(user, conversationId, command, progress, activation,
                previews.beginRequest(conversationId));
    }

    /** 异步任务在提交时取得版本，排队期间的后续请求也能使旧任务失效。 */
    public PreviewOutcome preview(CurrentUser user, String conversationId, PreviewCommand command,
                                  java.util.function.IntConsumer progress,
                                  java.util.function.Consumer<String> activation, long requestVersion) {
        long started = System.nanoTime();
        try {
            PreviewOutcome result = measuredPreview(user,conversationId,command,progress,activation,requestVersion);
            if (metrics != null) {
                metrics.record(user,"PREVIEW","*","-",result.status().name(),started);
                if (result.snapshot() != null) {
                    var p = result.snapshot().preview();
                    for (String id : DispatchVersionService.reportIds(p))
                        metrics.record(user,"PREVIEW_REPORT",id,p.getCatalogVersion(),result.status().name(),started);
                }
            }
            return result;
        } catch (RuntimeException e) {
            if (metrics != null) metrics.record(user,"PREVIEW","*","-","ERROR",started);
            throw e;
        }
    }

    private PreviewOutcome measuredPreview(CurrentUser user,String conversationId,PreviewCommand command,
            java.util.function.IntConsumer progress,java.util.function.Consumer<String> activation,long requestVersion) {
        String scopeMode = normalizeScope(command.scopeMode());
        ResolveResult resolution = resolve(user, command);
        if (resolution.matchType() == MatchType.NONE) {
            return PreviewOutcome.notFound(resolution);
        }
        if (resolution.matchType() == MatchType.AMBIGUOUS) {
            return PreviewOutcome.ambiguous(resolution);
        }

        List<CatalogEntry> dispatchable = catalogService.dispatchableReports(user);
        Scope scope = scope(user, conversationId, scopeMode, resolution, dispatchable);
        List<CatalogEntry> reports = dispatchable.stream().filter(e -> scope.ids().contains(e.reportId())).toList();
        if (reports.isEmpty()) {
            throw new ApiException(SCOPE_REMOVE.equals(scopeMode)
                    ? "排除之后没有可查询的报表，请确认要保留的报表范围" : ReportCatalogService.NOT_FOUND);
        }
        ResourceQuotaService.Permit permit = quotas == null ? null : quotas.acquire(user, "preview",
                reports.stream().map(CatalogEntry::reportId).toList());
        try {
        ResourceQuotaService.check(permit);
        java.util.function.IntConsumer guardedProgress = scanned -> {
            ResourceQuotaService.check(permit);
            progress.accept(scanned);
        };
        java.util.function.Consumer<String> guardedActivation = id -> {
            ResourceQuotaService.check(permit);
            activation.accept(id);
        };
        Set<String> companies = resolveCompanies(user, command.filters().companyCode());
        // 版本必须在求值之前读：求值期间有人发布规则时，快照带着旧版本，派单会被拒绝；
        // 反过来先求值后读版本，旧规则算出的结果会配上新版本，"规则变更后旧预览不能执行"就被绕过了
        VersionStamp stamp = versions.stamp(user, reports, companies);
        int maxItems = props.getPreview().getMaxItems();
        List<Candidate> candidates = candidateService.findCandidates(user.tenantId(), companies, reports,
                maxItems + 1, command.excludes(), guardedProgress);
        if (candidates.size() > maxItems) {
            return previewLarge(user, conversationId, command, resolution, scope, reports, companies, stamp,
                    scopeMode, guardedProgress, guardedActivation, requestVersion);
        }

        LocalDateTime now = LocalDateTime.now();
        DispatchPreview preview = newPreview(user, conversationId, command, resolution, scope, reports, companies,
                stamp, scopeMode, candidates, now);
        List<DispatchPreviewItem> items = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            items.add(toItem(preview.getId(), i, candidates.get(i)));
        }
        if (Thread.currentThread().isInterrupted()) throw new ApiException(409, "查询已取消");
        List<String> superseded = new ArrayList<>();
        List<String> expiredPlans = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            previews.lockConversation(conversationId);
            requireLatestRequest(conversationId, requestVersion);
            guardedActivation.accept(preview.getId());
            for (DispatchPreview old : previews.active(conversationId)) {
                if (previews.transition(old.getId(), DispatchPreview.ACTIVE, DispatchPreview.SUPERSEDED, StateReason.NEW_PREVIEW, now)) {
                    superseded.add(old.getId());
                }
            }
            for (DispatchPlan plan : plans.pending(conversationId)) {
                if (plans.transition(plan.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED, StateReason.NEW_PREVIEW, now)) {
                    expiredPlans.add(plan.getId());
                }
            }
            previews.insert(preview, items);
        });
        return PreviewOutcome.ok(resolution, new PreviewSnapshot(preview, items), superseded, expiredPlans);
        } finally {
            if (permit != null) permit.close();
        }
    }

    /** 当前用户的预览，读取时做懒惰校验；不归属当前用户按不存在处理 */
    public PreviewSnapshot getOwned(CurrentUser user, String previewId) {
        DispatchPreview preview = findOwned(user, previewId)
                .orElseThrow(() -> ApiException.notFound("预览不存在或已过期"));
        requireReadable(user, preview);
        preview = refresh(user, preview);
        return new PreviewSnapshot(preview, previews.page(preview.getId(), 0, 50));
    }

    public Optional<DispatchPreview> findOwned(CurrentUser user, String previewId) {
        return previews.find(previewId).filter(p -> PermissionService.owns(user, p.getTenantId(), p.getUserId()));
    }

    /** 本会话最近一次预览（不论状态） */
    public Optional<DispatchPreview> latest(CurrentUser user, String conversationId) {
        return previews.latest(user.tenantId(), user.userId(), conversationId);
    }

    /**
     * 懒惰校验：ACTIVE 的预览超过有效期，或目录 / 规则 / 权限版本与现在不一致，立即置为 EXPIRED，
     * 基于它的待确认清单一起失效。返回最新状态。
     */
    public DispatchPreview refresh(CurrentUser user, DispatchPreview preview) {
        if (!DispatchPreview.ACTIVE.equals(preview.getStatus())) {
            return preview;
        }
        LocalDateTime now = LocalDateTime.now();
        String reason = preview.getExpiresAt().isAfter(now) ? versions.verify(user, preview) : StateReason.TTL;
        if (reason == null) {
            return preview;
        }
        expire(preview, reason, now);
        return previews.find(preview.getId()).orElse(preview);
    }

    /** 预览失效：自身置为 EXPIRED，基于它的待确认清单一起失效 */
    public void expire(DispatchPreview preview, String reason, LocalDateTime now) {
        if (previews.transition(preview.getId(), DispatchPreview.ACTIVE, DispatchPreview.EXPIRED, reason, now)) {
            log.info("预览 {} 已失效：{}", preview.getId(), reason);
        }
        for (DispatchPlan plan : plans.pendingByPreview(preview.getId())) {
            plans.transition(plan.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED, reason, now);
        }
    }

    /** 已据此执行派单：预览不能再生成新的清单 */
    public void consume(String previewId, LocalDateTime now) {
        previews.transition(previewId, DispatchPreview.ACTIVE, DispatchPreview.CONSUMED, StateReason.EXECUTED, now);
    }

    public List<DispatchPreviewItem> items(String previewId) {
        return previews.items(previewId);
    }

    public List<Candidate> pageOwned(CurrentUser user, String previewId, int page, int size) {
        DispatchPreview preview = findOwned(user, previewId)
                .orElseThrow(() -> ApiException.notFound("预览不存在或已过期"));
        requireReadable(user, preview);
        if (page < 1 || size < 1 || size > 100) {
            throw new ApiException("页码或每页条数无效（每页最多 100 条）");
        }
        long offset = ((long) page - 1) * size;
        if (offset > Integer.MAX_VALUE) return List.of();
        return previews.page(previewId, (int) offset, size).stream().map(PreviewSnapshot::toCandidate).toList();
    }

    /** Historical data remains protected when an account loses a company or report grant. */
    public void requireReadable(CurrentUser user, DispatchPreview preview) {
        catalogService.refreshForValidation();
        requireReadableInCurrentCatalog(user, preview);
    }

    private void requireReadableInCurrentCatalog(CurrentUser user, DispatchPreview preview) {
        if (!Objects.equals(user.tenantId(), preview.getTenantId())
                || !user.companies().containsAll(DispatchVersionService.companies(preview))) {
            throw ApiException.forbidden("当前账号无权查看该预览的历史记录");
        }
        List<String> ids = DispatchVersionService.reportIds(preview);
        if (catalogService.inCatalogOrder(ids).stream()
                .filter(e -> Objects.equals(user.tenantId(), e.tenantId())
                        && user.hasPermission(e.permissionCode())).count()
                != new LinkedHashSet<>(ids).size()) {
            throw ApiException.forbidden("当前账号无权查看该预览的历史记录");
        }
    }

    public void requireConversationReadable(CurrentUser user, String conversationId) {
        List<DispatchPreview> snapshots = previews.byConversation(conversationId);
        if (snapshots.isEmpty()) return;
        catalogService.refreshForValidation();
        for (DispatchPreview preview : snapshots) requireReadableInCurrentCatalog(user, preview);
    }

    public Set<String> readableConversationIds(CurrentUser user, List<String> conversationIds) {
        if (conversationIds.isEmpty()) return Set.of();
        Set<String> readable = new LinkedHashSet<>(conversationIds);
        List<DispatchPreview> snapshots = previews.byConversations(conversationIds);
        if (snapshots.isEmpty()) return readable;
        catalogService.refreshForValidation();
        for (DispatchPreview preview : snapshots) {
            try {
                requireReadableInCurrentCatalog(user, preview);
            } catch (ApiException e) {
                readable.remove(preview.getConversationId());
            }
        }
        return readable;
    }

    private ResolveResult resolve(CurrentUser user, PreviewCommand command) {
        if (!command.reportIds().isEmpty()) {
            // 显式指定报表（选择卡片、REST）：逐个按权限校验，不存在与无权访问表现一致
            List<ReportRef> refs = new ArrayList<>();
            for (String id : new LinkedHashSet<>(command.reportIds())) {
                refs.add(catalogService.requireDispatchable(user, id).ref());
            }
            return new ResolveResult(MatchType.EXACT, null, refs, null, null,
                    refs.stream().map(ReportRef::reportName).toList(), null, false);
        }
        return catalogService.resolve(user, command.reportQuery());
    }

    private record Scope(Set<String> ids, boolean allReports) {
    }

    /**
     * 本次预览的报表范围。append / remove 以本会话上一次预览的范围为基础：
     * 上一次是“全部报表”时，基础就是当前全部可派单报表；没有上一次时，append 就是本次请求的报表，
     * remove 按“全部可派单报表减去指定”处理——绝不能退化成“只查指定报表”，那与用户意思相反。
     */
    private Scope scope(CurrentUser user, String conversationId, String mode, ResolveResult resolution,
                        List<CatalogEntry> dispatchable) {
        Set<String> all = new LinkedHashSet<>();
        dispatchable.forEach(e -> all.add(e.reportId()));
        boolean allRequested = resolution.matchType() == MatchType.ALL;
        Set<String> requested = new LinkedHashSet<>(resolution.reportIds());
        requested.retainAll(all);
        if (SCOPE_REPLACE.equals(mode)) {
            return allRequested ? new Scope(all, true) : new Scope(requested, false);
        }
        Optional<Scope> previous = latest(user, conversationId).map(p -> previousScope(p, all));
        if (SCOPE_APPEND.equals(mode)) {
            if (allRequested || previous.map(Scope::allReports).orElse(false)) {
                return new Scope(all, true);
            }
            Set<String> merged = new LinkedHashSet<>(previous.map(Scope::ids).orElse(Set.of()));
            merged.addAll(requested);
            return new Scope(merged, false);
        }
        if (allRequested) {
            throw new ApiException("排除之后没有可查询的报表，请确认要保留的报表范围");
        }
        Set<String> base = new LinkedHashSet<>(previous.filter(s -> !s.ids().isEmpty()).map(Scope::ids).orElse(all));
        base.removeAll(requested);
        if (base.isEmpty()) {
            throw new ApiException("排除之后没有可查询的报表，请确认要保留的报表范围");
        }
        return new Scope(base, false);
    }

    private static Scope previousScope(DispatchPreview preview, Set<String> currentAll) {
        if (Boolean.TRUE.equals(JsonUtil.toMap(preview.getQueryJson()).get("allReports"))) {
            return new Scope(currentAll, true);
        }
        Set<String> ids = new LinkedHashSet<>(DispatchVersionService.reportIds(preview));
        // 上一轮范围里用户现在已经看不到的报表不再带入
        ids.retainAll(currentAll);
        return new Scope(ids, false);
    }

    /** 提交任务前与实际查询时使用同一权限校验，拒绝把无权查询排队成“已发起”。 */
    public static Set<String> resolveCompanies(CurrentUser user, String companyCode) {
        if (companyCode == null || companyCode.isBlank()) {
            return user.companies();
        }
        String requested = companyCode.trim().toUpperCase(Locale.ROOT);
        if (!user.companies().contains(requested)) {
            throw new ApiException("当前账号无权查看 " + requested + " 公司，未返回其他公司的派单记录");
        }
        return Set.of(requested);
    }

    /** 预览阶段的排除项：优先按单据号精确匹配，未命中再按摘要关键词模糊匹配（用户常只说"云服务"这类描述） */
    public static List<Candidate> applyExcludes(List<Candidate> candidates, List<String> excludes) {
        if (candidates.isEmpty() || excludes == null || excludes.isEmpty()) {
            return candidates;
        }
        List<String> keys = excludes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (keys.isEmpty()) {
            return candidates;
        }
        Set<String> docNos = new LinkedHashSet<>();
        keys.forEach(k -> docNos.add(k.toUpperCase(Locale.ROOT)));
        return candidates.stream().filter(c -> {
            if (c.docNo() != null && docNos.contains(c.docNo().toUpperCase(Locale.ROOT))) {
                return false;
            }
            String label = c.label() == null ? "" : c.label();
            return keys.stream().noneMatch(label::contains);
        }).toList();
    }

    private DispatchPreview newPreview(CurrentUser user, String conversationId, PreviewCommand command, ResolveResult resolution,
                                       Scope scope, List<CatalogEntry> reports, Set<String> companies, VersionStamp stamp,
                                       String scopeMode, List<Candidate> candidates, LocalDateTime now) {
        List<String> reportIds = reports.stream().map(CatalogEntry::reportId).toList();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("companyCode", command.filters().companyCode());
        filters.put("companyCodes", List.copyOf(companies));
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("operation", command.operation());
        query.put("source", command.source());
        query.put("reportQuery", command.reportQuery());
        query.put("requestedReportIds", command.reportIds());
        query.put("matchType", resolution.matchType().name());
        query.put("matchedTerms", resolution.matchedTerms());
        query.put("unrecognized", resolution.unrecognized());
        query.put("scopeMode", scopeMode);
        query.put("reportIds", reportIds);
        query.put("allReports", scope.allReports());
        query.put("filters", filters);
        query.put("excludes", command.excludes());
        query.put("catalogVersions", stamp.catalogVersions());

        DispatchPreview p = new DispatchPreview();
        p.setId(JsonUtil.newId());
        p.setTenantId(user.tenantId());
        p.setUserId(user.userId());
        p.setConversationId(conversationId);
        p.setSource(command.source());
        p.setReportIds(JsonUtil.toJson(reportIds));
        p.setCompanyCodes(JsonUtil.toJson(List.copyOf(companies)));
        p.setQueryJson(JsonUtil.toJson(query));
        p.setSummaryJson(summaryJson(reports, candidates));
        p.setCatalogVersion(stamp.catalogVersion());
        p.setRuleVersion(stamp.ruleVersion());
        p.setPermissionVersion(stamp.permissionVersion());
        p.setStatus(DispatchPreview.ACTIVE);
        p.setTotalCount(candidates.size());
        p.setTotalAmount(candidates.stream().map(Candidate::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        p.setExpiresAt(now.plusMinutes(props.getPreview().getTtlMinutes()));
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return p;
    }

    private static DispatchPreviewItem toItem(String previewId, int seq, Candidate c) {
        DispatchPreviewItem i = new DispatchPreviewItem();
        i.setPreviewId(previewId);
        i.setSeq(seq);
        i.setReportId(c.reportId());
        i.setReportName(c.reportName());
        i.setCatalogVersion(c.catalogVersion());
        i.setRecordId(c.recordId());
        i.setDocNo(truncate(c.docNo(), DOC_NO_MAX));
        i.setCompanyCode(c.companyCode());
        i.setLabel(truncate(c.label(), LABEL_MAX));
        i.setAmount(c.amount());
        i.setBizDate(c.date());
        i.setRuleId(c.ruleId());
        i.setRuleName(c.ruleName());
        i.setRuleVersion(c.ruleVersion());
        i.setRuleDescription(c.ruleDescription());
        return i;
    }

    private PreviewOutcome previewLarge(CurrentUser user, String conversationId, PreviewCommand command,
                                        ResolveResult resolution, Scope scope, List<CatalogEntry> reports,
                                        Set<String> companies, VersionStamp stamp, String scopeMode,
                                        java.util.function.IntConsumer progress,
                                        java.util.function.Consumer<String> activation, long requestVersion) {
        LocalDateTime started = LocalDateTime.now();
        DispatchPreview preview = newPreview(user, conversationId, command, resolution, scope, reports, companies,
                stamp, scopeMode, List.of(), started);
        preview.setStatus(DispatchPreview.BUILDING);
        previews.insertBuilding(preview);
        List<DispatchPreviewItem> batch = new ArrayList<>(500);
        List<DispatchPreviewItem> firstPage = new ArrayList<>(50);
        Map<String, Summary> summary = emptySummary(reports);
        int[] sequence = {0};
        BigDecimal[] totalAmount = {BigDecimal.ZERO};
        List<String> superseded = new ArrayList<>();
        List<String> expiredPlans = new ArrayList<>();
        try {
            candidateService.scanCandidates(user.tenantId(), companies, reports, command.excludes(), progress, c -> {
                DispatchPreviewItem item = toItem(preview.getId(), sequence[0]++, c);
                if (firstPage.size() < 50) firstPage.add(item);
                batch.add(item);
                summary.get(c.reportId()).add(c);
                if (c.amount() != null) totalAmount[0] = totalAmount[0].add(c.amount());
                if (batch.size() == 500) {
                    previews.appendItems(batch);
                    batch.clear();
                }
            });
            if (!batch.isEmpty()) previews.appendItems(batch);
            if (Thread.currentThread().isInterrupted()) throw new ApiException(409, "查询已取消");
            String invalid = versions.verifyForRetry(user, preview);
            if (invalid != null) throw new ApiException(409, "查询期间报表或规则发生变化，请重新查询");
            preview.setTotalCount(sequence[0]);
            preview.setTotalAmount(totalAmount[0]);
            preview.setSummaryJson(summaryJson(summary));
            preview.setExpiresAt(LocalDateTime.now().plusMinutes(props.getPreview().getTtlMinutes()));
            preview.setUpdatedAt(LocalDateTime.now());
            previews.updateBuilding(preview);
            tx.executeWithoutResult(status -> {
                previews.lockConversation(conversationId);
                if (Thread.currentThread().isInterrupted()) throw new ApiException(409, "查询已取消");
                requireLatestRequest(conversationId, requestVersion);
                activation.accept(preview.getId());
                LocalDateTime now = LocalDateTime.now();
                for (DispatchPreview old : previews.active(conversationId)) {
                    if (previews.transition(old.getId(), DispatchPreview.ACTIVE, DispatchPreview.SUPERSEDED,
                            StateReason.NEW_PREVIEW, now)) superseded.add(old.getId());
                }
                for (DispatchPlan plan : plans.pending(conversationId)) {
                    if (plans.transition(plan.getId(), DispatchPlan.PENDING, DispatchPlan.EXPIRED,
                            StateReason.NEW_PREVIEW, now)) expiredPlans.add(plan.getId());
                }
                if (!previews.transition(preview.getId(), DispatchPreview.BUILDING, DispatchPreview.ACTIVE, null, now)) {
                    throw new ApiException(409, "预览已被取消");
                }
            });
            preview.setStatus(DispatchPreview.ACTIVE);
            return PreviewOutcome.ok(resolution, new PreviewSnapshot(preview, firstPage), superseded, expiredPlans);
        } catch (RuntimeException e) {
            previews.deleteBuilding(preview.getId());
            throw e;
        }
    }

    private void requireLatestRequest(String conversationId, long requestVersion) {
        if (!previews.isLatestRequest(conversationId, requestVersion)) {
            throw new ApiException(409, "该预览已被后续查询取代");
        }
    }

    private static Map<String, Summary> emptySummary(List<CatalogEntry> reports) {
        Map<String, Summary> summary = new LinkedHashMap<>();
        reports.forEach(r -> summary.put(r.reportId(), new Summary(r.reportId(), r.reportName())));
        return summary;
    }

    private static String summaryJson(List<CatalogEntry> reports, List<Candidate> candidates) {
        Map<String, Summary> summary = emptySummary(reports);
        candidates.forEach(c -> summary.get(c.reportId()).add(c));
        return summaryJson(summary);
    }

    private static String summaryJson(Map<String, Summary> summary) {
        List<Map<String, Object>> counts = new ArrayList<>();
        Map<String, String> rules = new LinkedHashMap<>();
        summary.values().forEach(s -> {
            counts.add(Map.of("reportId", s.id, "reportName", s.name, "count", s.count, "amount", s.amount));
            if (s.ruleDescription != null) rules.put(s.id, s.ruleDescription);
        });
        return JsonUtil.toJson(Map.of("byReport", counts, "ruleDescriptions", rules));
    }

    private static final class Summary {
        final String id;
        final String name;
        int count;
        BigDecimal amount = BigDecimal.ZERO;
        String ruleDescription;

        Summary(String id, String name) { this.id = id; this.name = name; }

        void add(Candidate candidate) {
            count++;
            if (candidate.amount() != null) amount = amount.add(candidate.amount());
            if (ruleDescription == null) ruleDescription = candidate.ruleDescription();
        }
    }

    static String normalizeScope(String scopeMode) {
        if (scopeMode == null || scopeMode.isBlank()) {
            return SCOPE_REPLACE;
        }
        String mode = scopeMode.trim().toLowerCase(Locale.ROOT);
        if (!SCOPE_REPLACE.equals(mode) && !SCOPE_APPEND.equals(mode) && !SCOPE_REMOVE.equals(mode)) {
            throw new ApiException("scopeMode 只能是 replace / append / remove");
        }
        return mode;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
