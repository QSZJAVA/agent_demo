package com.example.report.rule;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.FactRow;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.catalog.query.ReportQueryAdapter;
import com.example.report.entity.DispatchRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyCollection;

/**
 * 候选记录查找：报表来自目录，查询走适配器；规则求值出错的行按不命中处理，不能拖垮整次查询
 */
class DispatchCandidateServiceTest {

    private static final String CONTAINS_CLOUD = "string.contains(productName, '云')";

    private final RuleCache ruleCache = mock(RuleCache.class);
    private final DispatchCandidateService service = new DispatchCandidateService(ruleCache, new RuleEngine());
    private final CatalogEntry sales = new CatalogEntry("T001", "rpt-sales-order", "sales", "销售报表", "sales", null, "STANDARD", "{}",
            true, "PUBLISHED", 1, 3L, "report:sales", 10, null, null, null, "test", LocalDateTime.now(), List.of(),
            new SalesRows(), null);

    @Test
    void rowThatFailsToEvaluateIsSkippedInsteadOfFailingThePreview() {
        DispatchRule rule = new DispatchRule();
        rule.setTenantId("T001");
        rule.setId(7L);
        rule.setName("销售规则");
        rule.setVersion(2);
        rule.setExpression(CONTAINS_CLOUD);
        when(ruleCache.find(eq("T001"), eq("rpt-sales-order"), anyString())).thenReturn(Optional.of(rule));

        List<Candidate> result = service.findCandidates("T001", Set.of("A"), List.of(sales));
        assertEquals(List.of("SO1"), result.stream().map(Candidate::docNo).toList());
        Candidate hit = result.get(0);
        assertEquals("rpt-sales-order", hit.reportId());
        assertEquals("销售报表", hit.reportName());
        assertEquals(7L, hit.ruleId());
        assertEquals(2, hit.ruleVersion());
        assertEquals(3L, hit.catalogVersion(), "候选记录带着生成时的目录版本，进入快照与审计");
    }

    @Test
    void reportWithoutPublishedRuleProducesNoCandidates() {
        when(ruleCache.find(eq("T001"), eq("rpt-sales-order"), anyString())).thenReturn(Optional.empty());
        assertTrue(service.findCandidates("T001", Set.of("A"), List.of(sales)).isEmpty());
    }

    @Test
    void unusableReportIsSkipped() {
        CatalogEntry broken = new CatalogEntry("T001", "rpt-broken", "broken", "配置错误的报表", "x", null, "STANDARD", "{}", true,
                "PUBLISHED", 1, 1L, "report:x", 10, null, null, null, "test", LocalDateTime.now(), List.of(), null, "配置无效");
        assertTrue(service.findCandidates("T001", Set.of("A"), List.of(broken)).isEmpty());
    }

    @Test
    void dryRunReportsRowsThatFailToEvaluate() {
        DispatchCandidateService.DryRunResult result = service.dryRun("T001", sales, "A", CONTAINS_CLOUD, Set.of("A"));
        assertEquals(2, result.total());
        assertEquals(1, result.hitCount());
        assertEquals(1, result.errorCount());
        assertTrue(result.errorSample().startsWith("SO2"), result.errorSample());
    }

    @Test
    void executionChecksOnlyRequestedPendingIds() {
        ReportQueryAdapter adapter = mock(ReportQueryAdapter.class);
        CatalogEntry report = new CatalogEntry("T001", "rpt-sales-order", "sales", "销售报表", "sales", null,
                "STANDARD", "{}", true, "PUBLISHED", 1, 3L, "report:sales", 10,
                null, null, null, "test", LocalDateTime.now(), List.of(), adapter, null);
        DispatchRule rule = new DispatchRule();
        rule.setId(7L);
        rule.setName("云服务");
        rule.setVersion(1);
        rule.setExpression(CONTAINS_CLOUD);
        when(ruleCache.find("T001", "rpt-sales-order", "A")).thenReturn(Optional.of(rule));
        when(adapter.pendingRowsByIds(eq("T001"), anyCollection()))
                .thenReturn(List.of(SalesRows.row("SO1", "云服务")));

        assertEquals(Set.of("rpt-sales-order:1"), service.qualifiedPlanKeys("T001", Set.of("A"),
                List.of(report), Map.of("rpt-sales-order", List.of("1"))));
        verify(adapter, never()).pendingRows(eq("T001"), anySet());
    }

    /** 两行销售记录：一行产品名是"云服务"，一行产品名为空 */
    private static class SalesRows implements ReportQueryAdapter {

        @Override
        public List<FieldInfo> fields() {
            return List.of(new FieldInfo("productName", "string", "产品名称"));
        }

        @Override
        public String docNoLabel() {
            return "订单号";
        }

        @Override
        public List<FactRow> pendingRows(String tenantId, Set<String> companies) {
            return List.of(row("SO1", "云服务"), row("SO2", null));
        }

        @Override
        public List<FactRow> dryRunRowsAfter(String tenantId, Set<String> companies, String afterId, int size) {
            return afterId == null ? List.of(row("SO1", "云服务"), row("SO2", null)) : List.of();
        }

        @Override
        public List<FactRow> rowsByIds(String tenantId, Collection<String> recordIds) {
            return List.of();
        }

        private static FactRow row(String docNo, String productName) {
            // 事实模型里允许空值，Map.of 不接受 null
            Map<String, Object> facts = new HashMap<>();
            facts.put("productName", productName);
            return new FactRow(docNo.substring(2), docNo, "A", productName, BigDecimal.TEN, LocalDate.of(2026, 1, 1), facts);
        }
    }
}
