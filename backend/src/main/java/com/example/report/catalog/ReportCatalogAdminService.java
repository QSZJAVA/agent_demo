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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.report.operations.CatalogRevisions revisions;
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
        com.example.report.operations.OperationsPolicy.requireAdmin(admin);
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
        capture(d);
        catalog.broadcastRefresh();
        return d;
    }

    /** 修改定义：已发布的报表修改后立即生效，所以同样要通过发布前的校验*/
    @Transactional
    public ReportDefinition update(CurrentUser admin, String reportId, DefinitionForm form) {
        ReportDefinition d = require(admin, reportId);
        if (form.getExpectedVersion() != null && !form.getExpectedVersion().equals(d.getCatalogVersion())) {
            throw new ApiException(409,"目录已被其他管理员修改，请刷新后重试");
        }
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
        capture(d);
        catalog.broadcastRefresh();
        return d;
    }

    /** 发布前探测真实来源表、字段及唯一记录标识；通过后提升目录版本，保存快照并广播刷新。 */
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
        capture(d);
        catalog.broadcastRefresh();
        return d;
    }

    /** 停用：立即从所有用户的可见目录中消失，基于它的预览与待确认清单随目录版本变化失效*/
    @Transactional
    public ReportDefinition disable(CurrentUser admin, String reportId) {
        ReportDefinition d = require(admin, reportId);
        if (!ReportDefinition.STATUS_PUBLISHED.equals(d.getStatus())) {
            throw new ApiException("只有已发布的报表可以停用");
        }
        d.setStatus(ReportDefinition.STATUS_DISABLED);
        bump(admin, d);
        definitionMapper.updateById(d);
        capture(d);
        catalog.broadcastRefresh();
        return d;
    }

    @Transactional
    public ReportAlias addAlias(CurrentUser admin, String reportId, AliasForm form) {
        ReportDefinition d = require(admin, reportId);
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
            bump(admin, d);
            definitionMapper.updateById(d);
            capture(d);
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
        bump(admin, d);
        definitionMapper.updateById(d);
        capture(d);
        catalog.broadcastRefresh();
        return a;
    }

    /** 停用别名（保留记录便于追溯） */
    @Transactional
    public void disableAlias(CurrentUser admin, String reportId, Long aliasId) {
        ReportDefinition d = require(admin, reportId);
        ReportAlias a = aliasMapper.selectById(aliasId);
        if (a == null || !java.util.Objects.equals(admin.tenantId(), a.getTenantId()) || !a.getReportId().equals(reportId)) {
            throw ApiException.notFound("别名不存在");
        }
        a.setStatus(ReportAlias.STATUS_DISABLED);
        aliasMapper.updateById(a);
        bump(admin, d);
        definitionMapper.updateById(d);
        capture(d);
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
        for (DispatchRule rule : ruleMapper.publishedReportForUpdate(d.getTenantId(), d.getReportId())) {
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
        com.example.report.operations.OperationsPolicy.requireAdmin(admin);
        ReportDefinition d = reportId == null ? null : definitionMapper.lockById(reportId);
        if (d == null || !java.util.Objects.equals(admin.tenantId(), d.getTenantId())) {
            throw ApiException.notFound(ReportCatalogService.NOT_FOUND);
        }
        capture(d);
        return d;
    }

    private void capture(ReportDefinition d) {
        if (revisions != null) revisions.capture(d, aliasMapper.selectList(new LambdaQueryWrapper<ReportAlias>()
                .eq(ReportAlias::getTenantId,d.getTenantId()).eq(ReportAlias::getReportId,d.getReportId())));
    }

    /** 在当前版本匹配时恢复历史定义及别名，重新校验来源配置并生成更高的新版本，不能回退版本号绕过旧预览失效。 */
    @Transactional
    public ReportDefinition rollback(CurrentUser admin, String reportId, long target, long expectedVersion) {
        ReportDefinition current = require(admin,reportId);
        if (current.getCatalogVersion() != expectedVersion) throw new ApiException(409,"目录已变更，请刷新后重试");
        var saved = revisions.load(admin,reportId,target);
        ReportDefinition d = saved.definition();
        requireUniqueCode(admin.tenantId(),d.getReportCode(),reportId);
        if (ReportDefinition.STATUS_PUBLISHED.equals(d.getStatus())) checkPublishable(d);
        else adapterFactory.create(d.getQueryMode(),d.getQueryConfig());
        d.setCatalogVersion(current.getCatalogVersion());
        bump(admin,d);
        definitionMapper.updateById(d);
        // MyBatis skips null fields by default; rollback must also restore removed optional values.
        definitionMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ReportDefinition>()
                .eq(ReportDefinition::getReportId,reportId).eq(ReportDefinition::getTenantId,admin.tenantId())
                .set(ReportDefinition::getDescription,d.getDescription()).set(ReportDefinition::getOwnerUserId,d.getOwnerUserId())
                .set(ReportDefinition::getEffectiveFrom,d.getEffectiveFrom()).set(ReportDefinition::getEffectiveTo,d.getEffectiveTo()));
        aliasMapper.delete(new LambdaQueryWrapper<ReportAlias>().eq(ReportAlias::getTenantId,admin.tenantId()).eq(ReportAlias::getReportId,reportId));
        for (ReportAlias alias : saved.aliases()) {
            alias.setId(null);
            aliasMapper.insert(alias);
        }
        capture(d);
        catalog.broadcastRefresh();
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

    /** 报表定义维护请求；稳定标识创建后固定，更新绑定页面读取时的目录版本。*/
    @Data
    public static class DefinitionForm {
        /** 页面读取时的目录版本；提交更新时用于发现并发管理员修改。 */
        private Long expectedVersion;
        /** 可选，创建时指定稳定标识；之后不可修改*/
        private String reportId;
        /** 租户内唯一接口编码，可维护但不改变reportId。 */
        private String reportCode;
        /** 报表当前展示名称。*/
        private String reportName;
        /** 业务域编码，例如sales、receivable或expense。 */
        private String domainCode;
        /** 报表用途说明，可为空。*/
        private String description;
        /** STANDARD / ADAPTER */
        private String queryMode;
        /** JSON 对象或 JSON 字符串*/
        private Object queryConfig;
        /** 是否允许派单；发布状态、权限和当前记录状态仍须满足。 */
        private Boolean dispatchEnabled;
        /** 访问报表必须具备的业务权限码。*/
        private String permissionCode;
        /** 目录展示及汇总排序值。 */
        private Integer sortOrder;
        /** 报表业务负责人用户标识，可为空。*/
        private String ownerUserId;
        /** 生效开始时间，含边界；空表示不限制开始。 */
        private LocalDateTime effectiveFrom;
        /** 生效结束时间，不含边界；空表示长期有效。*/
        private LocalDateTime effectiveTo;
    }

    /** 目录别名维护请求；名称归一化后仍须校验冲突，不能把别名当作SQL输入。 */
    @Data
    public static class AliasForm {
        /** 用户口语、简称或历史名称。*/
        private String alias;
        /** SHORT / COLLOQUIAL / ENGLISH / HISTORICAL / DEPARTMENT / TYPO */
        private String aliasType;
        /** 解析候选排序权重；数值越大越优先。*/
        private Integer priority;
    }
}
