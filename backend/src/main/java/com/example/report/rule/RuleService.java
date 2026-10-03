package com.example.report.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.ApiException;
import com.example.report.entity.DispatchRule;
import com.example.report.entity.DispatchRuleHistory;
import com.example.report.mapper.DispatchRuleHistoryMapper;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则管理：草稿 → 试算 → 发布（旧版本自动停用）→ 回滚；每次动作落历史并广播刷新缓存。
 * 规则按 report_id 归属到报表目录；非管理员只能看到自己有权访问的报表的规则。
 */
@Service
public class RuleService {

    private final DispatchRuleMapper ruleMapper;
    private final DispatchRuleHistoryMapper historyMapper;
    private final RuleEngine ruleEngine;
    private final RuleCache ruleCache;
    private final DispatchCandidateService candidateService;
    private final ReportCatalogService catalogService;
    private final com.example.report.config.ResourceQuotaService quotas;

    public RuleService(DispatchRuleMapper ruleMapper, DispatchRuleHistoryMapper historyMapper, RuleEngine ruleEngine,
                       RuleCache ruleCache, DispatchCandidateService candidateService, ReportCatalogService catalogService,
                       com.example.report.config.ResourceQuotaService quotas) {
        this.ruleMapper = ruleMapper;
        this.historyMapper = historyMapper;
        this.ruleEngine = ruleEngine;
        this.ruleCache = ruleCache;
        this.candidateService = candidateService;
        this.catalogService = catalogService;
        this.quotas = java.util.Objects.requireNonNull(quotas);
    }

    public List<DispatchRule> list(CurrentUser user) {
        Set<String> visible = visibleReportIds(user);
        return ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                        .eq(DispatchRule::getTenantId, user.tenantId())
                        .orderByAsc(DispatchRule::getReportId)
                        .orderByAsc(DispatchRule::getCompanyCode)
                        .orderByDesc(DispatchRule::getVersion))
                .stream().filter(r -> visible == null || visible.contains(r.getReportId()))
                .filter(r -> inCompanyScope(user, r.getCompanyCode())).toList();
    }

    public List<DispatchRuleHistory> history(CurrentUser user, String reportId, String companyCode) {
        Set<String> visible = visibleReportIds(user);
        if (visible != null && visible.isEmpty()) {
            return List.of();
        }
        return historyMapper.selectList(new LambdaQueryWrapper<DispatchRuleHistory>()
                        .eq(DispatchRuleHistory::getTenantId, user.tenantId())
                        .eq(reportId != null && !reportId.isBlank(), DispatchRuleHistory::getReportId, reportId)
                        .eq(companyCode != null && !companyCode.isBlank(), DispatchRuleHistory::getCompanyCode, companyCode)
                        .in(visible != null, DispatchRuleHistory::getReportId, visible)
                        .and(q -> q.eq(DispatchRuleHistory::getCompanyCode, DispatchRule.ANY_COMPANY)
                                .or().in(!user.companies().isEmpty(), DispatchRuleHistory::getCompanyCode, user.companies()))
                        .orderByDesc(DispatchRuleHistory::getId)
                        .last("LIMIT 200"));
    }

    public List<FieldInfo> fields(CurrentUser user, String reportId) {
        return candidateService.fields(report(user, reportId));
    }

    /** 语法校验 + 变量必须是事实模型里的字段 */
    public Set<String> validate(CurrentUser user, String reportId, String expression) {
        return validate(report(user, reportId), expression);
    }

    private Set<String> validate(CatalogEntry report, String expression) {
        Set<String> vars = ruleEngine.variables(expression);
        Set<String> known = report.fields().stream().map(FieldInfo::name).collect(Collectors.toSet());
        List<String> unknown = vars.stream().filter(v -> !known.contains(v)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException("表达式引用了事实模型中不存在的字段：" + String.join("、", unknown));
        }
        return vars;
    }

    public DispatchCandidateService.DryRunResult dryRun(CurrentUser user, String reportId, String companyCode, String expression) {
        CatalogEntry report = report(user, reportId);
        validate(report, expression);
        String company = normalizeCompany(companyCode);
        requireCompanyScope(user, company);
        try (var permit = quotas.acquire(user, "rule-dry-run", List.of(report.reportId()))) {
            com.example.report.config.ResourceQuotaService.check(permit);
            return candidateService.dryRun(user.tenantId(), report.forUser(user), company, expression, user.companies(),
                    () -> com.example.report.config.ResourceQuotaService.check(permit));
        }
    }

    /** 新建或修改草稿；已发布的规则不能直接改，只能新建版本*/
    @Transactional
    public DispatchRule saveDraft(CurrentUser user, RuleForm form) {
        CatalogEntry report = report(user, form.getReportId());
        String company = normalizeCompany(form.getCompanyCode());
        requireCompanyScope(user, company);
        report = lockReport(user, report.reportId());
        validate(report, form.getExpression());
        LocalDateTime now = LocalDateTime.now();
        DispatchRule rule;
        if (form.getId() != null) {
            rule = authorizedRule(user, ruleMapper.lockRule(form.getId()));
            if (!DispatchRule.STATUS_DRAFT.equals(rule.getStatus())) {
                throw new ApiException("只有草稿可以修改，已发布的规则请新建版本");
            }
            if (!rule.getReportId().equals(report.reportId())) {
                throw new ApiException("草稿所属报表不能修改，请新建草稿");
            }
            if (!rule.getCompanyCode().equals(company)) throw new ApiException("草稿所属公司不能修改，请新建草稿");
        } else {
            rule = new DispatchRule();
            rule.setTenantId(user.tenantId());
            rule.setReportId(report.reportId());
            rule.setCompanyCode(company);
            rule.setVersion(nextVersion(user.tenantId(), rule.getReportId(), company));
            rule.setStatus(DispatchRule.STATUS_DRAFT);
            rule.setCreatedAt(now);
        }
        rule.setName(form.getName() == null || form.getName().isBlank() ? defaultName(report, rule) : form.getName().trim());
        rule.setDescription(form.getDescription());
        rule.setExpression(form.getExpression().trim());
        rule.setEffectiveFrom(form.getEffectiveFrom());
        rule.setEffectiveTo(form.getEffectiveTo());
        rule.setUpdatedBy(user.userId());
        rule.setUpdatedAt(now);
        if (rule.getId() == null) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }
        return rule;
    }

    /** 发布草稿：同范围之前已发布的版本自动停用 */
    /** 在目录锁和规则锁保护下发布版本，停用同范围旧规则并保存历史；变更使旧预览版本校验失效。*/
    @Transactional
    public DispatchRule publish(CurrentUser user, Long id) {
        DispatchRule rule = ruleForUpdate(user, id);
        if (!DispatchRule.STATUS_DRAFT.equals(rule.getStatus())) {
            throw new ApiException("只有草稿可以发布");
        }
        validate(report(user, rule.getReportId()), rule.getExpression());
        LocalDateTime now = LocalDateTime.now();
        disablePublished(user, rule.getReportId(), rule.getCompanyCode(), now);
        rule.setStatus(DispatchRule.STATUS_PUBLISHED);
        rule.setUpdatedBy(user.userId());
        rule.setUpdatedAt(now);
        ruleMapper.updateById(rule);
        history(rule, "publish", user.userId(), now);
        ruleCache.broadcastRefresh();
        return rule;
    }

    @Transactional
    public DispatchRule disable(CurrentUser user, Long id) {
        DispatchRule rule = ruleForUpdate(user, id);
        if (!DispatchRule.STATUS_PUBLISHED.equals(rule.getStatus())) {
            throw new ApiException("只有已发布的规则可以停用");
        }
        LocalDateTime now = LocalDateTime.now();
        rule.setStatus(DispatchRule.STATUS_DISABLED);
        rule.setUpdatedBy(user.userId());
        rule.setUpdatedAt(now);
        ruleMapper.updateById(rule);
        history(rule, "disable", user.userId(), now);
        ruleCache.broadcastRefresh();
        return rule;
    }

    /** 回滚到某个历史版本：复制其表达式为新版本并直接发布 */
    @Transactional
    public DispatchRule rollback(CurrentUser user, Long id) {
        DispatchRule target = ruleForUpdate(user, id);
        if (DispatchRule.STATUS_PUBLISHED.equals(target.getStatus())) {
            throw new ApiException("该版本已经是当前生效版本");
        }
        validate(report(user, target.getReportId()), target.getExpression());
        LocalDateTime now = LocalDateTime.now();
        disablePublished(user, target.getReportId(), target.getCompanyCode(), now);
        DispatchRule rule = new DispatchRule();
        rule.setTenantId(user.tenantId());
        rule.setReportId(target.getReportId());
        rule.setCompanyCode(target.getCompanyCode());
        rule.setName(target.getName());
        rule.setDescription(target.getDescription());
        rule.setExpression(target.getExpression());
        rule.setVersion(nextVersion(user.tenantId(), target.getReportId(), target.getCompanyCode()));
        rule.setStatus(DispatchRule.STATUS_PUBLISHED);
        rule.setCreatedAt(now);
        rule.setUpdatedBy(user.userId());
        rule.setUpdatedAt(now);
        ruleMapper.insert(rule);
        history(rule, "rollback", user.userId(), now);
        ruleCache.broadcastRefresh();
        return rule;
    }

    @Transactional
    public void deleteDraft(CurrentUser user, Long id) {
        DispatchRule rule = ruleForUpdate(user, id);
        if (!DispatchRule.STATUS_DRAFT.equals(rule.getStatus())) {
            throw new ApiException("只有草稿可以删除");
        }
        ruleMapper.deleteById(id);
    }

    private void disablePublished(CurrentUser user, String reportId, String companyCode, LocalDateTime now) {
        List<DispatchRule> published = ruleMapper.publishedForUpdate(user.tenantId(), reportId, companyCode);
        for (DispatchRule old : published) {
            old.setStatus(DispatchRule.STATUS_DISABLED);
            old.setUpdatedBy(user.userId());
            old.setUpdatedAt(now);
            ruleMapper.updateById(old);
            history(old, "disable", user.userId(), now);
        }
    }

    /**
     * 按 ID 操作规则时的归属校验：规则所属报表对当前用户可见（管理员为目录内任意报表），且公司在其数据范围内。
     * 不满足时按不存在处理，不暴露其他范围的规则。
     */
    private DispatchRule requireRule(CurrentUser user, Long id) {
        return authorizedRule(user, id == null ? null : ruleMapper.selectById(id));
    }

    /**
     * Lock order shared with directory edits: report first, then rule rows. Use current reads,
     * because callers may already have an older REPEATABLE READ snapshot.
     */
    private DispatchRule ruleForUpdate(CurrentUser user, Long id) {
        DispatchRule reference = requireRule(user, id);
        lockReport(user, reference.getReportId());
        return authorizedRule(user, ruleMapper.lockRule(id));
    }

    private CatalogEntry lockReport(CurrentUser user, String reportId) {
        var definition = ruleMapper.lockReport(user.tenantId(), reportId);
        if (definition == null) throw ApiException.notFound("报表不存在");
        CatalogEntry current = report(user, reportId);
        if (!java.util.Objects.equals(definition.getCatalogVersion(), current.catalogVersion())) {
            throw new ApiException(409, "报表目录版本已变化，请刷新后重试规则修改");
        }
        return current;
    }

    private DispatchRule authorizedRule(CurrentUser user, DispatchRule rule) {
        if (rule == null || !java.util.Objects.equals(user.tenantId(), rule.getTenantId()) || !inCompanyScope(user, rule.getCompanyCode())) {
            throw ApiException.notFound("规则不存在");
        }
        try {
            report(user, rule.getReportId());
        } catch (ApiException e) {
            throw ApiException.notFound("规则不存在");
        }
        return rule;
    }

    /** 通配规则（*）对所有公司生效，任何能访问该报表的用户都在范围内；具体公司的规则要求公司在用户数据范围内 */
    private static boolean inCompanyScope(CurrentUser user, String companyCode) {
        if (DispatchRule.ANY_COMPANY.equals(companyCode)) {
            return true;
        }
        return companyCode != null && user.companies().contains(companyCode);
    }

    private static void requireCompanyScope(CurrentUser user, String company) {
        if (!inCompanyScope(user, company)) {
            throw ApiException.forbidden("公司 " + company + " 不在您的可见范围内");
        }
    }

    /** 管理员可以为目录中任意报表（含草稿）维护规则；其他人只能访问可见报表*/
    private CatalogEntry report(CurrentUser user, String reportId) {
        if (reportId == null || reportId.isBlank()) {
            throw new ApiException("请指定报表");
        }
        if (user.admin()) {
            return catalogService.find(reportId.trim()).filter(e -> user.tenantId().equals(e.tenantId())).orElseThrow(() -> ApiException.notFound(ReportCatalogService.NOT_FOUND));
        }
        return catalogService.requireVisible(user, reportId.trim());
    }

    /** null 表示不过滤（管理员） */
    private Set<String> visibleReportIds(CurrentUser user) {
        if (user.admin()) {
            return null;
        }
        return catalogService.visibleReports(user).stream().map(CatalogEntry::reportId).collect(Collectors.toSet());
    }

    private int nextVersion(String tenantId, String reportId, String companyCode) {
        Integer latest = ruleMapper.latestVersionForUpdate(tenantId, reportId, companyCode);
        return latest == null ? 1 : Math.addExact(latest, 1);
    }

    private void history(DispatchRule rule, String action, String operator, LocalDateTime at) {
        DispatchRuleHistory h = new DispatchRuleHistory();
        h.setRuleId(rule.getId());
        h.setTenantId(rule.getTenantId());
        h.setReportId(rule.getReportId());
        h.setLegacyReportType(rule.getLegacyReportType());
        h.setCompanyCode(rule.getCompanyCode());
        h.setVersion(rule.getVersion());
        h.setName(rule.getName());
        h.setExpression(rule.getExpression());
        h.setDescription(rule.getDescription());
        h.setAction(action);
        h.setOperatedBy(operator);
        h.setOperatedAt(at);
        historyMapper.insert(h);
    }

    private static String normalizeCompany(String companyCode) {
        if (companyCode == null || companyCode.isBlank()) {
            return DispatchRule.ANY_COMPANY;
        }
        return companyCode.trim().toUpperCase();
    }

    private static String defaultName(CatalogEntry report, DispatchRule rule) {
        String scope = DispatchRule.ANY_COMPANY.equals(rule.getCompanyCode()) ? "默认" : rule.getCompanyCode() + " 公司";
        return report.reportName() + scope + "规则 v" + rule.getVersion();
    }

    /** 规则草稿维护请求；版本和发布状态由服务端控制，调用方不能直接指定。*/
    @Data
    public static class RuleForm {
        /** 修改的草稿主键；创建新草稿时为空。 */
        private Long id;
        /** 报表目录中的稳定标识*/
        private String reportId;
        /** 规则公司范围，*表示通配；须在操作者授权范围内。 */
        private String companyCode;
        /** 规则展示名称，未指定时由服务端按报表和版本生成。*/
        private String name;
        /** 供用户理解的规则说明，可为空。 */
        private String description;
        /** Aviator表达式；变量必须属于当前报表事实字段。*/
        private String expression;
        /** 生效开始时间，含边界；空表示不限制开始时间。 */
        private LocalDateTime effectiveFrom;
        /** 生效结束时间，不含边界；空表示不限制结束时间。*/
        private LocalDateTime effectiveTo;
    }
}
