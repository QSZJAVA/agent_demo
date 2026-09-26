package com.example.report.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.catalog.query.QueryAdapterFactory;
import com.example.report.catalog.query.ReportQueryAdapter;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.entity.DispatchRule;
import com.example.report.entity.ReportAlias;
import com.example.report.entity.ReportDefinition;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.mapper.ReportAliasMapper;
import com.example.report.mapper.ReportDefinitionMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.RuleEngine;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 报表目录维护（管理员）：新增报表、修改定义、发布、停用、维护别名。
 * 新增一张标准报表只需要：登记定义与查询配置 → 维护别名 → 发布 → 为它发布派单规则，不需要改代码、正则、枚举或提示词。
 * 定义每变更一次 catalog_version 加 1，基于旧版本生成的预览随之不能再执行；别名只影响识别，不改版本。
 */
@Service
public class ReportCatalogAdminService {

    private static final Pattern REPORT_ID = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Set<String> ALIAS_TYPES = Set.of("SHORT", "COLLOQUIAL", "ENGLISH", "HISTORICAL", "DEPARTMENT", "TYPO");

    private final ReportDefinitionMapper definitionMapper;
    private final ReportAliasMapper aliasMapper;
    private final ReportCatalog catalog;
    private final QueryAdapterFactory adapterFactory;
    private final DispatchRuleMapper ruleMapper;
    private final RuleEngine ruleEngine;

    public ReportCatalogAdminService(ReportDefinitionMapper definitionMapper, ReportAliasMapper aliasMapper,
                                     ReportCatalog catalog, QueryAdapterFactory adapterFactory,
                                     DispatchRuleMapper ruleMapper, RuleEngine ruleEngine) {
        this.definitionMapper = definitionMapper;
        this.aliasMapper = aliasMapper;
        this.catalog = catalog;
        this.adapterFactory = adapterFactory;
        this.ruleMapper = ruleMapper;
        this.ruleEngine = ruleEngine;
    }

    /** 登记一张新报表（草稿）。report_id 可以由管理员指定（跨环境保持一致），不指定时自动生成；创建后不可修改 */
    @Transactional
    public ReportDefinition create(CurrentUser admin, DefinitionForm form) {
        String reportId = form.getReportId() == null || form.getReportId().isBlank()
                ? "rpt-" + JsonUtil.newId().substring(0, 12) : form.getReportId().trim();
        if (!REPORT_ID.matcher(reportId).matches()) {
            throw new ApiException("report_id 只能由小写字母、数字和短横线组成，以字母开头，3~64 位");
        }
        if (definitionMapper.selectById(reportId) != null) {
            throw new ApiException("report_id 已存在：" + reportId);
        }
        LocalDateTime now = LocalDateTime.now();
        ReportDefinition d = new ReportDefinition();
        d.setReportId(reportId);
        d.setTenantId(admin.tenantId());
        apply(form, d);
        adapterFactory.create(d.getQueryMode(), d.getQueryConfig());
        requireUniqueCode(admin.tenantId(), d.getReportCode(), reportId);
        d.setStatus(ReportDefinition.STATUS_DRAFT);
        d.setSchemaVersion(1);
        d.setCatalogVersion(1L);
        d.setCreatedBy(admin.userId());
        d.setCreatedAt(now);
        d.setUpdatedBy(admin.userId());
        d.setUpdatedAt(now);
        definitionMapper.insert(d);
        catalog.broadcastRefresh();
        return d;
    }

    /** 修改定义：已发布的报表修改后立即生效，所以同样要通过发布前的校验 */
    @Transactional
    public ReportDefinition update(CurrentUser admin, String reportId, DefinitionForm form) {
        ReportDefinition d = require(admin, reportId);
        if (form.getReportId() != null && !form.getReportId().isBlank() && !form.getReportId().trim().equals(reportId)) {
            throw new ApiException("report_id 创建后不可修改");
        }
        apply(form, d);
        requireUniqueCode(admin.tenantId(), d.getReportCode(), reportId);
        if (ReportDefinition.STATUS_PUBLISHED.equals(d.getStatus())) {
            checkPublishable(d);
        } else {
            adapterFactory.create(d.getQueryMode(), d.getQueryConfig());
        }
        bump(admin, d);
        definitionMapper.updateById(d);
        catalog.broadcastRefresh();
        return d;
    }

    @Transactional
    public ReportDefinition publish(CurrentUser admin, String reportId) {
        ReportDefinition d = require(admin, reportId);
        if (ReportDefinition.STATUS_PUBLISHED.equals(d.getStatus())) {
            throw new ApiException("该报表已经发布");
        }
        checkPublishable(d);
        d.setStatus(ReportDefinition.STATUS_PUBLISHED);
        bump(admin, d);
        definitionMapper.updateById(d);
        catalog.broadcastRefresh();
        return d;
    }

    /** 停用：立即从所有用户的可见目录中消失，基于它的预览与待确认清单随目录版本变化失效 */
    @Transactional
    public ReportDefinition disable(CurrentUser admin, String reportId) {
        ReportDefinition d = require(admin, reportId);
        if (!ReportDefinition.STATUS_PUBLISHED.equals(d.getStatus())) {
            throw new ApiException("只有已发布的报表可以停用");
        }
        d.setStatus(ReportDefinition.STATUS_DISABLED);
        bump(admin, d);
        definitionMapper.updateById(d);
        catalog.broadcastRefresh();
        return d;
    }

    @Transactional
    public ReportAlias addAlias(CurrentUser admin, String reportId, AliasForm form) {
        require(admin, reportId);
        String alias = form.getAlias() == null ? "" : form.getAlias().trim();
        if (TextNormalizer.normalize(alias).length() < 2) {
            throw new ApiException("别名至少两个字");
        }
        String type = form.getAliasType() == null ? "COLLOQUIAL" : form.getAliasType().trim().toUpperCase();
        if (!ALIAS_TYPES.contains(type)) {
            throw new ApiException("别名类型只能是 " + ALIAS_TYPES);
        }
        ReportAlias existing = aliasMapper.selectOne(new LambdaQueryWrapper<ReportAlias>()
                .eq(ReportAlias::getTenantId, admin.tenantId())
                .eq(ReportAlias::getReportId, reportId).eq(ReportAlias::getAlias, alias));
        if (existing != null) {
            existing.setAliasType(type);
            existing.setPriority(form.getPriority() == null ? 0 : form.getPriority());
            existing.setStatus(ReportAlias.STATUS_ACTIVE);
            aliasMapper.updateById(existing);
            catalog.broadcastRefresh();
            return existing;
        }
        ReportAlias a = new ReportAlias();
        a.setReportId(reportId);
        a.setTenantId(admin.tenantId());
        a.setAlias(alias);
        a.setAliasType(type);
        a.setPriority(form.getPriority() == null ? 0 : form.getPriority());
        a.setStatus(ReportAlias.STATUS_ACTIVE);
        a.setCreatedBy(admin.userId());
        a.setCreatedAt(LocalDateTime.now());
        aliasMapper.insert(a);
        catalog.broadcastRefresh();
        return a;
    }

    /** 停用别名（保留记录便于追溯） */
    @Transactional
    public void disableAlias(CurrentUser admin, String reportId, Long aliasId) {
        require(admin, reportId);
        ReportAlias a = aliasMapper.selectById(aliasId);
        if (a == null || !java.util.Objects.equals(admin.tenantId(), a.getTenantId()) || !a.getReportId().equals(reportId)) {
            throw ApiException.notFound("别名不存在");
        }
        a.setStatus(ReportAlias.STATUS_DISABLED);
        aliasMapper.updateById(a);
        catalog.broadcastRefresh();
    }

    /**
     * 发布前校验：配置能解析、表和列在库里真实存在、权限码已配置；
     * 已经为这张报表发布的规则引用的字段在新配置里必须都还在，否则规则会静默失效。
     */
    private void checkPublishable(ReportDefinition d) {
        if (d.getPermissionCode() == null || d.getPermissionCode().isBlank()) {
            throw new ApiException("发布前必须配置报表权限码");
        }
        ReportQueryAdapter adapter = adapterFactory.createAndProbe(d.getQueryMode(), d.getQueryConfig());
        Set<String> fields = adapter.fields().stream().map(FieldInfo::name).collect(Collectors.toSet());
        List<String> broken = new ArrayList<>();
        for (DispatchRule rule : ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                .eq(DispatchRule::getTenantId, d.getTenantId())
                .eq(DispatchRule::getReportId, d.getReportId())
                .eq(DispatchRule::getStatus, DispatchRule.STATUS_PUBLISHED))) {
            List<String> missing = ruleEngine.variables(rule.getExpression()).stream().filter(v -> !fields.contains(v)).toList();
            if (!missing.isEmpty()) {
                broken.add(rule.getName() + "（" + String.join("、", missing) + "）");
            }
        }
        if (!broken.isEmpty()) {
            throw new ApiException("以下已发布规则引用了新配置中不存在的字段，请先调整规则：" + String.join("；", broken));
        }
    }

    private void apply(DefinitionForm form, ReportDefinition d) {
        if (form.getReportCode() != null) {
            d.setReportCode(form.getReportCode().trim());
        }
        if (form.getReportName() != null) {
            d.setReportName(form.getReportName().trim());
        }
        if (form.getDomainCode() != null) {
            d.setDomainCode(form.getDomainCode().trim());
        }
        if (form.getDescription() != null) {
            d.setDescription(form.getDescription().trim());
        }
        if (form.getQueryMode() != null) {
            d.setQueryMode(form.getQueryMode().trim().toUpperCase());
        }
        if (form.getQueryConfig() != null) {
            d.setQueryConfig(form.getQueryConfig() instanceof String s ? s : JsonUtil.toJson(form.getQueryConfig()));
        }
        if (form.getDispatchEnabled() != null) {
            d.setDispatchEnabled(form.getDispatchEnabled());
        }
        if (form.getPermissionCode() != null) {
            d.setPermissionCode(form.getPermissionCode().trim());
        }
        if (form.getSortOrder() != null) {
            d.setSortOrder(form.getSortOrder());
        }
        if (form.getOwnerUserId() != null) {
            d.setOwnerUserId(form.getOwnerUserId().trim());
        }
        d.setEffectiveFrom(form.getEffectiveFrom() != null ? form.getEffectiveFrom() : d.getEffectiveFrom());
        d.setEffectiveTo(form.getEffectiveTo() != null ? form.getEffectiveTo() : d.getEffectiveTo());
        if (blank(d.getReportCode()) || blank(d.getReportName()) || blank(d.getDomainCode()) || blank(d.getQueryMode())
                || blank(d.getQueryConfig()) || blank(d.getPermissionCode())) {
            throw new ApiException("报表编码、名称、业务域、查询方式、查询配置、权限码都不能为空");
        }
        if (d.getDispatchEnabled() == null) {
            d.setDispatchEnabled(false);
        }
        if (d.getSortOrder() == null) {
            d.setSortOrder(100);
        }
        if (d.getEffectiveFrom() != null && d.getEffectiveTo() != null && !d.getEffectiveTo().isAfter(d.getEffectiveFrom())) {
            throw new ApiException("生效结束时间必须晚于开始时间");
        }
    }

    private void requireUniqueCode(String tenantId, String reportCode, String reportId) {
        ReportDefinition other = definitionMapper.selectOne(new LambdaQueryWrapper<ReportDefinition>()
                .eq(ReportDefinition::getTenantId, tenantId)
                .eq(ReportDefinition::getReportCode, reportCode));
        if (other != null && !other.getReportId().equals(reportId)) {
            throw new ApiException("报表编码已被 " + other.getReportName() + " 使用：" + reportCode);
        }
    }

    private ReportDefinition require(CurrentUser admin, String reportId) {
        ReportDefinition d = reportId == null ? null : definitionMapper.selectById(reportId);
        if (d == null || !java.util.Objects.equals(admin.tenantId(), d.getTenantId())) {
            throw ApiException.notFound(ReportCatalogService.NOT_FOUND);
        }
        return d;
    }

    private static void bump(CurrentUser admin, ReportDefinition d) {
        d.setCatalogVersion((d.getCatalogVersion() == null ? 1L : d.getCatalogVersion()) + 1);
        d.setUpdatedBy(admin.userId());
        d.setUpdatedAt(LocalDateTime.now());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Data
    public static class DefinitionForm {
        /** 可选，创建时指定稳定标识；之后不可修改 */
        private String reportId;
        private String reportCode;
        private String reportName;
        private String domainCode;
        private String description;
        /** STANDARD / ADAPTER */
        private String queryMode;
        /** JSON 对象或 JSON 字符串 */
        private Object queryConfig;
        private Boolean dispatchEnabled;
        private String permissionCode;
        private Integer sortOrder;
        private String ownerUserId;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
    }

    @Data
    public static class AliasForm {
        private String alias;
        /** SHORT / COLLOQUIAL / ENGLISH / HISTORICAL / DEPARTMENT / TYPO */
        private String aliasType;
        private Integer priority;
    }
}
