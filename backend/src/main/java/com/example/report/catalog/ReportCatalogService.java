package com.example.report.catalog;

import com.example.report.common.ApiException;
import com.example.report.common.Digests;
import com.example.report.config.AgentProperties;
import com.example.report.permission.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 目录与权限绑定（P0-04）：所有面向用户的目录访问都先按权限过滤，报表解析也只在过滤后的范围内进行。
 * 报表不存在、未发布、不在生效期、配置无效、用户没有权限，对外一律表现为“不存在或无权访问”，不泄露内部信息。
 */
@Service
public class ReportCatalogService {

    public static final String NOT_FOUND = "报表不存在或无权访问";

    private final ReportCatalog catalog;
    private final ReportResolver resolver;

    public ReportCatalogService(ReportCatalog catalog, AgentProperties props) {
        this.catalog = catalog;
        this.resolver = new ReportResolver(props.getResolver().getFuzzyThreshold(), props.getResolver().getAmbiguityMargin());
    }

    /** 已发布、在生效期、配置可用、用户有该报表的权限 */
    public boolean isVisible(CurrentUser user, CatalogEntry entry) {
        return java.util.Objects.equals(user.tenantId(), entry.tenantId()) && entry.published() && entry.effectiveAt(LocalDateTime.now()) && entry.usable()
                && user.hasPermission(entry.permissionCode());
    }

    public boolean isDispatchable(CurrentUser user, CatalogEntry entry) {
        return entry.dispatchEnabled() && isVisible(user, entry);
    }

    public List<CatalogEntry> visibleReports(CurrentUser user) {
        return catalog.all().stream().filter(e -> isVisible(user, e)).toList();
    }

    /** 当前用户可以通过 Agent 派单的报表：可见且目录中启用了派单 */
    public List<CatalogEntry> dispatchableReports(CurrentUser user) {
        return catalog.all().stream().filter(e -> isDispatchable(user, e)).toList();
    }

    public CatalogEntry requireVisible(CurrentUser user, String reportId) {
        return catalog.find(reportId).filter(e -> isVisible(user, e)).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    public CatalogEntry requireDispatchable(CurrentUser user, String reportId) {
        return catalog.find(reportId).filter(e -> isDispatchable(user, e)).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    /** 旧接口仍按 Demo 时期的 reportType 访问时，经映射表找到目录，再做同样的权限校验 */
    public CatalogEntry requireVisibleByLegacyCode(CurrentUser user, String legacyCode) {
        String reportId = catalog.reportIdForLegacyCode(legacyCode).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        return requireVisible(user, reportId);
    }

    /** 报表 ID 或旧编码都接受，统一转成可见报表 */
    public CatalogEntry requireVisibleByIdOrLegacyCode(CurrentUser user, String reportIdOrCode) {
        if (reportIdOrCode == null || reportIdOrCode.isBlank()) {
            throw new ApiException("报表不能为空");
        }
        String key = reportIdOrCode.trim();
        if (catalog.find(key).isPresent()) {
            return requireVisible(user, key);
        }
        return requireVisibleByLegacyCode(user, key);
    }

    /** 在当前用户可派单的报表范围内解析说法 */
    public ResolveResult resolve(CurrentUser user, String query) {
        List<ReportRef> visible = dispatchableReports(user).stream().map(CatalogEntry::ref).toList();
        return resolver.resolve(query, catalog.terms(), visible);
    }

    /** 可派单报表的说法索引与可见 ID，供服务端兜底识别意图用 */
    public Set<String> dispatchableIds(CurrentUser user) {
        return dispatchableReports(user).stream().map(CatalogEntry::reportId).collect(Collectors.toSet());
    }

    public TermIndex terms() {
        return catalog.terms();
    }

    public Optional<CatalogEntry> find(String reportId) {
        return catalog.find(reportId);
    }

    /** 按目录顺序排列指定报表；不存在的 ID 被忽略 */
    public List<CatalogEntry> inCatalogOrder(Collection<String> reportIds) {
        return catalog.all().stream().filter(e -> reportIds.contains(e.reportId())).toList();
    }

    /** 范围内每张报表的目录版本 */
    public static Map<String, Long> versions(Collection<CatalogEntry> entries) {
        Map<String, Long> versions = new LinkedHashMap<>();
        entries.stream().sorted(Comparator.comparing(CatalogEntry::reportId))
                .forEach(e -> versions.put(e.reportId(), e.catalogVersion()));
        return versions;
    }

    /** 范围内报表目录版本的指纹：任何一张报表的定义变化、被停用或不再可见，指纹都会变 */
    public static String fingerprint(Collection<CatalogEntry> entries) {
        String canonical = versions(entries).entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(","));
        return Digests.sha256("catalog|" + canonical);
    }
}
