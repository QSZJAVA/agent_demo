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
