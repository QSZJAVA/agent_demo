package com.example.report.dispatch;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.JsonUtil;
import com.example.report.entity.DispatchPreview;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.RuleCache;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 版本绑定：预览记录目录 / 规则 / 权限三个版本，之后每次使用都重新计算比对。
 * 顺序固定为 权限 → 目录 → 规则，返回第一个不一致的原因，页面据此给出“权限范围已变化”“报表目录已变更”“派单规则已更新”。
 */
@Component
public class DispatchVersionService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ReportCatalogService catalogService;
    private final RuleCache ruleCache;

    public DispatchVersionService(ReportCatalogService catalogService, RuleCache ruleCache) {
        this.catalogService = catalogService;
        this.ruleCache = ruleCache;
    }

    /** 必须在求值之前调用：求值期间有人发布规则时，快照带着旧版本，后续使用会被拒绝 */
    public VersionStamp stamp(CurrentUser user, Collection<CatalogEntry> reports, Collection<String> companies) {
        ruleCache.reload();
        List<String> reportIds = reports.stream().map(CatalogEntry::reportId).toList();
        return new VersionStamp(ReportCatalogService.fingerprint(reports), ReportCatalogService.versions(reports),
                ruleCache.fingerprint(user.tenantId(), reportIds, companies), user.permissionVersion());
    }

    /** 预览的版本与现在是否一致；不一致返回原因（{@link StateReason}），一致返回 null*/
    public String verify(CurrentUser user, DispatchPreview preview) {
        if (!DispatchPreview.ACTIVE.equals(preview.getStatus())) {
            return preview.getStatusReason() == null ? StateReason.TTL : preview.getStatusReason();
        }
        if (!preview.getExpiresAt().isAfter(java.time.LocalDateTime.now())) {
            return StateReason.TTL;
        }
        return verifyVersions(user, preview);
    }

    /** 已消费预览上的明确失败项重试：不复用原预览状态，只重新检查权限、目录和规则版本。 */
    public String verifyForRetry(CurrentUser user, DispatchPreview preview) {
        return verifyVersions(user, preview);
    }

    private String verifyVersions(CurrentUser user, DispatchPreview preview) {
        if (!Objects.equals(user.permissionVersion(), preview.getPermissionVersion())) {
            return StateReason.PERMISSION_CHANGED;
        }
        catalogService.refreshForValidation();
        ruleCache.reload();
        List<String> reportIds = reportIds(preview);
        List<CatalogEntry> reports = catalogService.inCatalogOrder(reportIds).stream()
                .filter(e -> catalogService.isDispatchable(user, e))
                .toList();
        // 范围内有报表被停用、下线、关闭派单或不再可见，都算目录变化
        if (reports.size() != new LinkedHashSet<>(reportIds).size()
                || !Objects.equals(ReportCatalogService.fingerprint(reports), preview.getCatalogVersion())) {
            return StateReason.CATALOG_CHANGED;
        }
        if (!"manual".equals(preview.getSource())
                && !Objects.equals(ruleCache.fingerprint(user.tenantId(), reportIds, companies(preview)), preview.getRuleVersion())) {
            return StateReason.RULE_CHANGED;
        }
        return null;
    }

    public static List<String> reportIds(DispatchPreview preview) {
        return readList(preview.getReportIds());
    }

    public static Set<String> companies(DispatchPreview preview) {
        return new LinkedHashSet<>(readList(preview.getCompanyCodes()));
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("预览范围数据缺失");
        }
        try {
            return List.copyOf(JsonUtil.MAPPER.readValue(json, STRING_LIST));
        } catch (Exception e) {
            throw new IllegalStateException("预览范围数据损坏：" + json, e);
        }
    }
}
