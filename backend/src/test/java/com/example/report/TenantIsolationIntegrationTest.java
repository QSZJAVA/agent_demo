package com.example.report;

import com.example.report.catalog.*;
import com.example.report.catalog.query.DispatchStatusWriter;
import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import com.example.report.report.ReportService;
import com.example.report.rule.RuleCache;
import com.example.report.rule.RuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 同用户、公司和别名的跨租户读写隔离回归；执行者必须将数据源指向已授权的专用临时库。 */
@EnabledIfEnvironmentVariable(named = "DEMO_IT", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
class TenantIsolationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportCatalog catalog;
    @Autowired ReportCatalogService catalogService;
    @Autowired ReportCatalogAdminService adminService;
    @Autowired RuleService rules;
    @Autowired RuleCache ruleCache;
    @Autowired ReportService reports;
    @Autowired TransactionTemplate tx;

    @Test void historyLimitAppliesAfterCompanyScope() {
        CurrentUser scoped = new CurrentUser("T001", "scoped", "Scoped", Set.of("A"), Set.of("report:sales"), false);
        String marker = "history-limit-it";
        try {
            jdbc.update("INSERT INTO dispatch_rule_history (tenant_id,rule_id,report_id,company_code,version,expression,action,operated_by) "
                    + "VALUES ('T001',1,'rpt-sales-order','A',1,'true','publish',?)", marker);
            for (int i = 0; i < 201; i++) {
                jdbc.update("INSERT INTO dispatch_rule_history (tenant_id,rule_id,report_id,company_code,version,expression,action,operated_by) "
                        + "VALUES ('T001',1,'rpt-sales-order','B',1,'true','publish',?)", marker);
            }
            assertTrue(rules.history(scoped, "rpt-sales-order", null).stream()
                    .anyMatch(h -> marker.equals(h.getOperatedBy()) && "A".equals(h.getCompanyCode())));
            assertTrue(rules.history(scoped, "rpt-sales-order", null).stream()
                    .noneMatch(h -> "B".equals(h.getCompanyCode())));
        } finally {
            jdbc.update("DELETE FROM dispatch_rule_history WHERE operated_by = ?", marker);
        }
    }

    @Test void sameUserCompanyCodeAndAliasAreIsolatedAcrossTenants() {
        CurrentUser first = new CurrentUser("T001", "admin", "First", Set.of("A"), Set.of("*"), true);
        CurrentUser second = new CurrentUser("T002", "admin", "Second", Set.of("A"), Set.of("*"), true);
        String id = "rpt-tenant-test";
        String prefix = "TENANT-IT-";
        try {
            ReportCatalogAdminService.DefinitionForm form = new ReportCatalogAdminService.DefinitionForm();
            form.setReportId(id);
            form.setReportCode("sales"); // same code as tenant T001
            form.setReportName("第二租户销售报表");
            form.setDomainCode("sales");
            form.setQueryMode("STANDARD");
            form.setQueryConfig(catalog.find("rpt-sales-order").orElseThrow().queryConfig());
            form.setPermissionCode("report:sales");
            form.setDispatchEnabled(true);
            assertEquals("T002", adminService.create(second, form).getTenantId());
            var alias = new ReportCatalogAdminService.AliasForm();
            alias.setAlias("销售台账");
            var savedAlias = adminService.addAlias(second, id, alias);
            adminService.publish(second, id);
            assertEquals(List.of(id), catalogService.resolve(second, "销售台账").reportIds());
            assertEquals(List.of("rpt-sales-order"), catalogService.resolve(first, "销售台账").reportIds());
            assertThrows(ApiException.class, () -> adminService.disable(first, id));
            assertThrows(ApiException.class, () -> adminService.disableAlias(first, id, savedAlias.getId()));
            assertThrows(ApiException.class, () -> catalogService.requireVisible(first, id));

            RuleService.RuleForm rule = new RuleService.RuleForm();
            rule.setReportId(id);
            rule.setExpression("amount > 20");
            var published = rules.publish(second, rules.saveDraft(second, rule).getId());
            assertTrue(rules.list(first).stream().noneMatch(r -> "T002".equals(r.getTenantId())));
            assertTrue(rules.list(second).stream().allMatch(r -> "T002".equals(r.getTenantId())));
            assertTrue(rules.history(first, id, null).isEmpty());
            assertEquals(1, rules.history(second, id, null).size());
            assertThrows(ApiException.class, () -> rules.disable(first, published.getId()));
            assertTrue(ruleCache.find("T001", id, "A").isEmpty());
            assertEquals(published.getId(), ruleCache.find("T002", id, "A").orElseThrow().getId());

            jdbc.update("INSERT INTO report_sales (tenant_id,company_code,order_no,amount,sale_date) VALUES ('T001','A',?,100,NOW()),('T002','A',?,100,NOW())",
                    prefix + "1", prefix + "2");
            String firstId = jdbc.queryForObject("SELECT id FROM report_sales WHERE order_no = ?", String.class, prefix + "1");
            String secondId = jdbc.queryForObject("SELECT id FROM report_sales WHERE order_no = ?", String.class, prefix + "2");
            var adapter = catalogService.requireVisible(second, id).adapter();
            assertEquals(List.of(secondId), adapter.rowsByIds("T002", List.of(firstId, secondId)).stream().map(r -> r.recordId()).toList());
            assertEquals(List.of(prefix + "2"), adapter.pendingRows("T002", Set.of("A")).stream().map(r -> r.docNo()).toList());
            // 原子复核必须与状态写入处于同一事务；保留跨租户拒绝和本租户成功的原始断言。
            assertEquals(Boolean.FALSE, tx.execute(status -> ((DispatchStatusWriter) adapter).markDispatchedGuarded("T002", firstId, "A", LocalDateTime.now(), row -> true)));
            assertEquals(Boolean.TRUE, tx.execute(status -> ((DispatchStatusWriter) adapter).markDispatchedGuarded("T002", secondId, "A", LocalDateTime.now(), row -> true)));
            assertEquals(List.of(prefix + "2"), reports.listSales(second).stream().map(r -> r.getOrderNo()).toList());
        } finally {
            jdbc.update("DELETE FROM report_sales WHERE order_no IN (?, ?)", prefix + "1", prefix + "2");
            jdbc.update("DELETE FROM dispatch_rule_history WHERE tenant_id = 'T002' AND report_id = ?", id);
            jdbc.update("DELETE FROM dispatch_rule WHERE tenant_id = 'T002' AND report_id = ?", id);
            jdbc.update("DELETE FROM report_alias WHERE tenant_id = 'T002' AND report_id = ?", id);
            jdbc.update("DELETE FROM report_definition WHERE tenant_id = 'T002' AND report_id = ?", id);
            catalog.reload();
            ruleCache.reload();
        }
    }
}
