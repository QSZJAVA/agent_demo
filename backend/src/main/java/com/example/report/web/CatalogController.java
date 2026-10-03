package com.example.report.web;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalog;
import com.example.report.catalog.ReportCatalogAdminService;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.catalog.ReportRef;
import com.example.report.catalog.ResolveResult;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.common.Result;
import com.example.report.entity.ReportAlias;
import com.example.report.entity.ReportDefinition;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报表目录：查询接口按权限过滤（不可见报表与不存在表现一致）；维护接口仅管理员。
 */
@RestController
@RequestMapping("/api/report-catalog")
public class CatalogController {

    private final PermissionService permissionService;
    private final ReportCatalogService catalogService;
    private final ReportCatalogAdminService adminService;
    private final ReportCatalog catalog;

    public CatalogController(PermissionService permissionService, ReportCatalogService catalogService,
                             ReportCatalogAdminService adminService, ReportCatalog catalog) {
        this.permissionService = permissionService;
        this.catalogService = catalogService;
        this.adminService = adminService;
        this.catalog = catalog;
    }

    /**
     * 当前用户可见的报表目录；管理员传 manage=true 时返回全部报表（含草稿、停用、配置错误）的完整定义
     */
    @GetMapping
    public Result<List<?>> list(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                @RequestParam(defaultValue = "false") boolean manage) {
        CurrentUser user = permissionService.resolve(userId);
        if (manage) {
            permissionService.requireAdmin(user);
            return Result.ok(catalog.all().stream().filter(e -> user.tenantId().equals(e.tenantId())).map(CatalogController::adminView).toList());
        }
        return Result.ok(catalogService.visibleReports(user).stream().map(CatalogController::view).toList());
    }

    /** 在当前用户可派单的报表范围内解析一个说法：返回解析类型与命中的报表（不含任何不可见报表的信息） */
    @GetMapping("/search")
    public Result<SearchResult> search(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                       @RequestParam(defaultValue = "") String q) {
        CurrentUser user = permissionService.resolve(userId);
        ResolveResult r = catalogService.resolve(user, q);
        return Result.ok(new SearchResult(r.matchType().name(), r.reports(), r.candidates(), r.matchedTerms(),
                r.unrecognized(), r.noAccessibleReports()));
    }

    @GetMapping("/{reportId}")
    public Result<?> get(@RequestHeader(PermissionService.USER_HEADER) String userId, @PathVariable String reportId) {
        CurrentUser user = permissionService.resolve(userId);
        if (user.admin()) {
            return Result.ok(adminView(catalog.find(reportId).filter(e -> user.tenantId().equals(e.tenantId()))
                    .orElseThrow(() -> ApiException.notFound(ReportCatalogService.NOT_FOUND))));
        }
        return Result.ok(view(catalogService.requireVisible(user, reportId)));
    }

    @PostMapping
    public Result<ReportDefinition> create(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                           @RequestBody ReportCatalogAdminService.DefinitionForm form) {
        CurrentUser user = admin(userId);
        return Result.ok(adminService.create(user, form));
    }

    @PutMapping("/{reportId}")
    public Result<ReportDefinition> update(@RequestHeader(PermissionService.USER_HEADER) String userId,
    @PathVariable String reportId,
                                           @RequestBody ReportCatalogAdminService.DefinitionForm form) {
        CurrentUser user = admin(userId);
        return Result.ok(adminService.update(user, reportId, form));
    }

    @PostMapping("/{reportId}/publish")
    public Result<ReportDefinition> publish(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                            @PathVariable String reportId) {
        return Result.ok(adminService.publish(admin(userId), reportId));
    }

    @PostMapping("/{reportId}/disable")
    public Result<ReportDefinition> disable(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                            @PathVariable String reportId) {
        return Result.ok(adminService.disable(admin(userId), reportId));
    }

    @PostMapping("/{reportId}/aliases")
    public Result<ReportAlias> addAlias(@RequestHeader(PermissionService.USER_HEADER) String userId,
    @PathVariable String reportId,
                                        @RequestBody ReportCatalogAdminService.AliasForm form) {
        return Result.ok(adminService.addAlias(admin(userId), reportId, form));
    }

    @DeleteMapping("/{reportId}/aliases/{aliasId}")
    public Result<Void> disableAlias(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                     @PathVariable String reportId, @PathVariable Long aliasId) {
        CurrentUser user = admin(userId);
        adminService.disableAlias(user, reportId, aliasId);
        return Result.ok(null);
    }

    private CurrentUser admin(String userId) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        return user;
    }

    /**
     * 普通用户看到的目录项：不含查询配置、权限码等内部信息
     * @param reportId 稳定报表标识，关联报表目录
     * @param reportCode 租户内报表接口编码
     * @param reportName 报表展示名称
     * @param domainCode 业务域编码
     * @param description 业务用途或字段含义说明
     * @param dispatchEnabled 是否允许派单，仍须验证发布状态及业务授权
     * @param docNoLabel 单据号展示标签
     * @param aliases 报表别名集合
     * @param fields 报表事实字段契约集合
     */
    public record CatalogView(String reportId, String reportCode, String reportName, String domainCode, String description,
                              boolean dispatchEnabled, String docNoLabel, List<String> aliases, List<FieldInfo> fields) {
    }

    /**
     * 管理员看到的完整定义
     * @param reportId 稳定报表标识，关联报表目录
     * @param reportCode 租户内报表接口编码
     * @param reportName 报表展示名称
     * @param domainCode 业务域编码
     * @param description 业务用途或字段含义说明
     * @param queryMode STANDARD字段映射或ADAPTER专用适配器
     * @param queryConfig 服务器维护的查询映射JSON，不允许模型提供SQL
     * @param dispatchEnabled 是否允许派单，仍须验证发布状态及业务授权
     * @param status 当前业务状态，以所属状态机为准
     * @param catalogVersion 目录版本或聚合指纹，用于发现预览后定义变更
     * @param permissionCode 访问报表所需权限码
     * @param sortOrder 展示及汇总排序值
     * @param effectiveFrom 生效开始时间，含边界；为空不限制开始时间
     * @param effectiveTo 生效结束时间，不含边界；为空不限制结束时间
     * @param ownerUserId 报表负责人用户标识，未配置时为空
     * @param updatedBy 最后修改人用户标识
     * @param updatedAt 最后更新时间
     * @param aliases 报表别名集合
     * @param fields 报表事实字段契约集合
     * @param configError 配置探测失败的摘要；无错误时为空
     */
    public record CatalogAdminView(String reportId, String reportCode, String reportName, String domainCode,
                                   String description, String queryMode, Object queryConfig, boolean dispatchEnabled,
                                   String status, long catalogVersion, String permissionCode, int sortOrder,
                                   LocalDateTime effectiveFrom, LocalDateTime effectiveTo, String ownerUserId,
                                   String updatedBy, LocalDateTime updatedAt, List<CatalogEntry.AliasView> aliases,
                                   List<FieldInfo> fields, String configError) {
    }

    /**
     * 报表名称解析结果，歧义和未识别部分必须供用户核对。
     * @param matchType 名称匹配类型；歧义或未识别不能自动执行
     * @param reports 当前用户可见的报表引用集合
     * @param candidates 需用户确认的可见报表候选集合
     * @param matchedTerms 在输入中命中的目录词条
     * @param unrecognized 未被可靠解析的输入部分，不能静默丢弃
     * @param noAccessibleReports 当前用户无可用报表的标记
     */
    public record SearchResult(String matchType, List<ReportRef> reports, List<ReportRef> candidates,
                               List<String> matchedTerms, List<String> unrecognized, boolean noAccessibleReports) {
    }

    private static CatalogView view(CatalogEntry e) {
        return new CatalogView(e.reportId(), e.reportCode(), e.reportName(), e.domainCode(), e.description(),
                e.dispatchEnabled(), e.docNoLabel(), e.activeAliases().stream().map(CatalogEntry.AliasView::alias).toList(),
                e.fields());
    }

    private static CatalogAdminView adminView(CatalogEntry e) {
        return new CatalogAdminView(e.reportId(), e.reportCode(), e.reportName(), e.domainCode(), e.description(),
                e.queryMode(), JsonUtil.toMap(e.queryConfig()), e.dispatchEnabled(), e.status(), e.catalogVersion(),
                e.permissionCode(), e.sortOrder(), e.effectiveFrom(), e.effectiveTo(), e.ownerUserId(), e.updatedBy(),
                e.updatedAt(), e.aliases(), e.fields(), e.configError());
    }
}
