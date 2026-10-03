package com.example.report.catalog;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.catalog.query.ReportQueryAdapter;
import com.example.report.entity.ReportAlias;
import com.example.report.entity.ReportDefinition;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 目录快照中的一张报表：定义、别名、查询适配器。不可变，目录刷新时整体替换。
 *
 * @param adapter     按 query_config 创建的查询适配器；配置无效时为 null，此时报表不可用
 * @param configError 配置无效的原因
 * @param tenantId 数据所属租户标识，来自服务端身份
 * @param reportId 稳定报表标识，关联报表目录
 * @param reportCode 租户内报表接口编码
 * @param reportName 报表展示名称
 * @param domainCode 业务域编码
 * @param description 业务用途或字段含义说明
 * @param queryMode STANDARD字段映射或ADAPTER专用适配器
 * @param queryConfig 服务器维护的查询映射JSON，不允许模型提供SQL
 * @param dispatchEnabled 是否允许派单，仍须验证发布状态及业务授权
 * @param status 当前业务状态，以所属状态机为准
 * @param schemaVersion 事实结构版本号
 * @param catalogVersion 目录版本或聚合指纹，用于发现预览后定义变更
 * @param permissionCode 访问报表所需权限码
 * @param sortOrder 展示及汇总排序值
 * @param effectiveFrom 生效开始时间，含边界；为空不限制开始时间
 * @param effectiveTo 生效结束时间，不含边界；为空不限制结束时间
 * @param ownerUserId 报表负责人用户标识，未配置时为空
 * @param updatedBy 最后修改人用户标识
 * @param updatedAt 最后更新时间
 * @param aliases 报表别名集合
 */
public record CatalogEntry(
        String tenantId,
        String reportId,
        String reportCode,
        String reportName,
        String domainCode,
        String description,
        String queryMode,
        String queryConfig,
        boolean dispatchEnabled,
        String status,
        int schemaVersion,
        long catalogVersion,
        String permissionCode,
        int sortOrder,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        String ownerUserId,
        String updatedBy,
        LocalDateTime updatedAt,
        List<AliasView> aliases,
        ReportQueryAdapter adapter,
        String configError
) {

    /**
     * 业务数据契约；字段含义及有效范围如下。
     * @param id 报表别名记录的数据库主键
     * @param alias 报表别名文本
     * @param aliasType 别名类型，例如SHORT、COLLOQUIAL或HISTORICAL
     * @param priority 候选排序优先级，数值越大越靠前
     * @param status ACTIVE启用或DISABLED停用
     */
    public record AliasView(Long id, String alias, String aliasType, int priority, String status) {
        public boolean active() {
            return ReportAlias.STATUS_ACTIVE.equals(status);
        }
    }

    public CatalogEntry {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public static CatalogEntry of(ReportDefinition d, List<ReportAlias> aliases, ReportQueryAdapter adapter, String configError) {
        List<AliasView> views = aliases.stream()
                .map(a -> new AliasView(a.getId(), a.getAlias(), a.getAliasType(),
                        a.getPriority() == null ? 0 : a.getPriority(), a.getStatus()))
                .toList();
        return new CatalogEntry(d.getTenantId(), d.getReportId(), d.getReportCode(), d.getReportName(), d.getDomainCode(), d.getDescription(),
                d.getQueryMode(), d.getQueryConfig(), Boolean.TRUE.equals(d.getDispatchEnabled()), d.getStatus(),
                d.getSchemaVersion() == null ? 1 : d.getSchemaVersion(),
                d.getCatalogVersion() == null ? 1L : d.getCatalogVersion(), d.getPermissionCode(),
                d.getSortOrder() == null ? 0 : d.getSortOrder(), d.getEffectiveFrom(), d.getEffectiveTo(),
                d.getOwnerUserId(), d.getUpdatedBy(), d.getUpdatedAt(), views, adapter, configError);
    }

    public boolean published() {
        return ReportDefinition.STATUS_PUBLISHED.equals(status);
    }

    public boolean effectiveAt(LocalDateTime now) {
        return (effectiveFrom == null || !effectiveFrom.isAfter(now)) && (effectiveTo == null || effectiveTo.isAfter(now));
    }

    public boolean usable() {
        return adapter != null;
    }

    public List<AliasView> activeAliases() {
        return aliases.stream().filter(AliasView::active).toList();
    }

    public List<FieldInfo> fields() {
        return adapter == null ? List.of() : adapter.fields();
    }

    public String docNoLabel() {
        return adapter == null ? "单据号" : adapter.docNoLabel();
    }

    public ReportRef ref() {
        return new ReportRef(reportId, reportName, domainCode, description);
    }

    public CatalogEntry forUser(com.example.report.permission.CurrentUser user) {
        if (!(adapter instanceof com.example.report.mcp.McpReportQueryAdapter)) return this;
        return new CatalogEntry(tenantId,reportId,reportCode,reportName,domainCode,description,queryMode,queryConfig,
                dispatchEnabled,status,schemaVersion,catalogVersion,permissionCode,sortOrder,effectiveFrom,effectiveTo,
                ownerUserId,updatedBy,updatedAt,aliases,adapter.forUser(user),configError);
    }
}
