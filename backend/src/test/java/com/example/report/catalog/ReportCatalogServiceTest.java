package com.example.report.catalog;

import com.example.report.common.ApiException;
import com.example.report.config.AgentProperties;
import com.example.report.permission.CurrentUser;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.example.report.support.TestCatalog.EXPENSE;
import static com.example.report.support.TestCatalog.RECEIVABLE;
import static com.example.report.support.TestCatalog.SALES;
import static com.example.report.support.TestCatalog.USER1;
import static com.example.report.support.TestCatalog.USER3;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 目录与权限绑定（P0-04）：可见 = 已发布 + 在生效期 + 配置可用 + 有报表权限；可派单还要求目录开启派单
 */
class ReportCatalogServiceTest {

    private final TestCatalog catalog = new TestCatalog();
    private final ReportCatalogService service = new ReportCatalogService(catalog.catalog(), new AgentProperties());

    private static List<String> ids(List<CatalogEntry> entries) {
        return entries.stream().map(CatalogEntry::reportId).toList();
    }

    @Test
    void sameCompanyAndPermissionsNeverGrantAnotherTenantsReports() {
        CurrentUser other = new CurrentUser("T002", "user1", "Other", Set.of("A"), Set.of(CurrentUser.ALL), true);
        assertTrue(service.visibleReports(other).isEmpty());
        assertTrue(service.resolve(other, "销售台账").reports().isEmpty());
        assertThrows(ApiException.class, () -> service.requireVisible(other, SALES));
        assertThrows(ApiException.class, () -> service.requireVisible(other, SALES));
    }

    @Test
    void visibilityFollowsReportPermissions() {
        assertEquals(List.of(SALES, RECEIVABLE, EXPENSE), ids(service.visibleReports(USER1)));
        assertEquals(List.of(SALES, EXPENSE), ids(service.visibleReports(USER3)));
        assertEquals(List.of(SALES, RECEIVABLE, EXPENSE), ids(service.visibleReports(TestCatalog.ADMIN)));
        CurrentUser nobody = new CurrentUser("T001", "guest", "访客", Set.of("A"), Set.of(), false);
        assertTrue(service.visibleReports(nobody).isEmpty());
    }

    @Test
    void draftDisabledOutOfWindowAndBrokenReportsAreInvisible() {
        catalog.replace(TestCatalog.with(catalog.get(SALES), 2, "DRAFT", true));
        catalog.replace(TestCatalog.with(catalog.get(RECEIVABLE), 2, "DISABLED", true));
        CatalogEntry e = catalog.get(EXPENSE);
        catalog.replace(new CatalogEntry("T001", e.reportId(), e.reportCode(), e.reportName(), e.domainCode(), e.description(),
                e.queryMode(), e.queryConfig(), true, "PUBLISHED", 1, 1L, e.permissionCode(), e.sortOrder(),
                LocalDateTime.now().plusDays(1), null, null, null, null, e.aliases(), e.adapter(), null));
        assertTrue(service.visibleReports(USER1).isEmpty());

        catalog.set(TestCatalog.demo());
        CatalogEntry s = catalog.get(SALES);
        catalog.replace(new CatalogEntry("T001", s.reportId(), s.reportCode(), s.reportName(), s.domainCode(), s.description(),
                s.queryMode(), s.queryConfig(), true, "PUBLISHED", 1, 1L, s.permissionCode(), s.sortOrder(), null, null,
                null, null, null, s.aliases(), null, "查询配置无效"));
        assertEquals(List.of(RECEIVABLE, EXPENSE), ids(service.visibleReports(USER1)), "配置无效的报表不可用");
    }

    @Test
    void dispatchableRequiresDispatchEnabled() {
        catalog.replace(TestCatalog.with(catalog.get(SALES), 1, "PUBLISHED", false));
        assertEquals(List.of(SALES, RECEIVABLE, EXPENSE), ids(service.visibleReports(USER1)));
        assertEquals(List.of(RECEIVABLE, EXPENSE), ids(service.dispatchableReports(USER1)));
    }

    @Test
    void hiddenAndMissingReportsLookTheSame() {
        ApiException hidden = assertThrows(ApiException.class, () -> service.requireVisible(USER3, RECEIVABLE));
        ApiException missing = assertThrows(ApiException.class, () -> service.requireVisible(USER3, "rpt-nope"));
        assertEquals(hidden.getCode(), missing.getCode());
        assertEquals(hidden.getMessage(), missing.getMessage());
        assertThrows(ApiException.class, () -> service.requireVisible(USER3, RECEIVABLE));
        assertEquals(SALES, service.requireVisible(USER3, SALES).reportId());
        assertEquals(EXPENSE, service.requireVisible(USER3, EXPENSE).reportId());
    }

    @Test
    void catalogFingerprintTracksDefinitionVersions() {
        List<CatalogEntry> scope = List.of(catalog.get(SALES), catalog.get(EXPENSE));
        String before = ReportCatalogService.fingerprint(scope);
        assertEquals(before, ReportCatalogService.fingerprint(List.of(catalog.get(EXPENSE), catalog.get(SALES))));
        assertNotEquals(before, ReportCatalogService.fingerprint(List.of(TestCatalog.with(catalog.get(SALES), 2, "PUBLISHED", true),
                catalog.get(EXPENSE))));
        assertNotEquals(before, ReportCatalogService.fingerprint(List.of(catalog.get(SALES))), "范围里少了一张报表也算变化");
    }

    @Test
    void permissionVersionChangesWithScope() {
        CurrentUser same = new CurrentUser("T001", "user1", "别名", Set.of("A"), Set.of("report:expense", "report:sales",
                "report:receivable"), false);
        assertEquals(USER1.permissionVersion(), same.permissionVersion(), "与集合顺序、展示名无关");
        assertNotEquals(USER1.permissionVersion(), new CurrentUser("T001", "user1", "", Set.of("A", "B"),
                USER1.permissions(), false).permissionVersion());
        assertNotEquals(USER1.permissionVersion(), new CurrentUser("T001", "user1", "", Set.of("A"),
                Set.of("report:sales"), false).permissionVersion());
        assertNotEquals(USER1.permissionVersion(), new CurrentUser("T002", "user1", "", Set.of("A"),
                USER1.permissions(), false).permissionVersion());
    }
}
