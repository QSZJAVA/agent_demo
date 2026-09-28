package com.example.report.rule;

import com.example.report.catalog.*;
import com.example.report.catalog.query.*;
import com.example.report.common.ApiException;
import com.example.report.config.*;
import com.example.report.mapper.*;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.example.report.support.TestCatalog.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedDryRunTest {
    final ReportQueryAdapter adapter = mock(ReportQueryAdapter.class);
    final CatalogEntry report = spy(new TestCatalog().get(SALES));
    final DispatchCandidateService candidates = new DispatchCandidateService(mock(RuleCache.class), new RuleEngine());

    void data(int count) {
        doReturn(adapter).when(report).adapter();
        when(adapter.dryRunRowsAfter(eq("T001"), eq(Set.of("A")), nullable(String.class), eq(500))).thenAnswer(call -> {
            String cursor = call.getArgument(2);
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            return java.util.stream.IntStream.range(start, Math.min(start + 500, count))
                    .mapToObj(i -> new FactRow("" + (i + 1), "SO" + i, "A", "item", BigDecimal.TEN,
                            null, Map.<String,Object>of("amount", i))).toList();
        });
    }

    @Test void exactTotalsCrossPageBoundariesAndOnlyTwentySamplesAreKept() {
        data(1201);
        var r = candidates.dryRun("T001", report, "A", "amount >= 500", Set.of("A"));
        assertEquals(1201, r.total()); assertEquals(701, r.hitCount()); assertEquals(20, r.samples().size());
        assertEquals("501", r.samples().get(0).recordId());
        verify(adapter, times(3)).dryRunRowsAfter(eq("T001"), eq(Set.of("A")), nullable(String.class), eq(500));
        verify(adapter, never()).pendingRows(anyString(), anySet());
    }

    @Test void scanLimitRejectsInsteadOfReturningPartialStatistics() {
        data(10001);
        assertEquals(422, assertThrows(ApiException.class,
                () -> candidates.dryRun("T001", report, "A", "amount >= 0", Set.of("A"))).getCode());
        verify(adapter, times(21)).dryRunRowsAfter(eq("T001"), eq(Set.of("A")), nullable(String.class), eq(500));
        verify(adapter, never()).pendingRows(anyString(), anySet());
    }

    @Test void exactLimitAndEmptyDataAreValid() {
        data(10000);
        assertEquals(10000, candidates.dryRun("T001", report, "A", "true", Set.of("A")).total());
        data(0);
        assertEquals(0, candidates.dryRun("T001", report, "A", "true", Set.of("A")).total());
    }

    @Test void interruptionAndUnsupportedAdapterDoNotTriggerFullLoad() {
        data(5);
        Thread.currentThread().interrupt();
        try {
            assertEquals(408, assertThrows(ApiException.class,
                    () -> candidates.dryRun("T001", report, "A", "true", Set.of("A"))).getCode());
            verifyNoInteractions(adapter);
        } finally { Thread.interrupted(); }
        doCallRealMethod().when(adapter).dryRunRowsAfter(anyString(), anySet(), nullable(String.class), anyInt());
        assertThrows(ApiException.class, () -> candidates.dryRun("T001", report, "A", "true", Set.of("A")));
        verify(adapter, never()).pendingRows(anyString(), anySet());
    }

    @Test void quotaDenialPreventsScanningAndPermitClosesOnSuccessOrFailure() {
        var quotas = mock(ResourceQuotaService.class);
        var scanning = mock(DispatchCandidateService.class);
        var service = new RuleService(mock(DispatchRuleMapper.class), mock(DispatchRuleHistoryMapper.class),
                new RuleEngine(), mock(RuleCache.class), scanning,
                new ReportCatalogService(new TestCatalog().catalog(), new AgentProperties()), quotas);
        when(quotas.acquire(ADMIN, "rule-dry-run", List.of(SALES))).thenThrow(new ApiException(429, "quota"));
        assertEquals(429, assertThrows(ApiException.class, () -> service.dryRun(ADMIN, SALES, "A", "amount > 0")).getCode());
        verifyNoInteractions(scanning);
        var first = mock(ResourceQuotaService.Permit.class);
        var second = mock(ResourceQuotaService.Permit.class);
        doReturn(first, second).when(quotas).acquire(ADMIN, "rule-dry-run", List.of(SALES));
        service.dryRun(ADMIN, SALES, "A", "amount > 0");
        verify(first).close();
        when(scanning.dryRun(anyString(), any(), anyString(), anyString(), anySet(), any(Runnable.class))).thenThrow(new ApiException(422, "limit"));
        assertThrows(ApiException.class, () -> service.dryRun(ADMIN, SALES, "A", "amount > 0"));
        verify(second).close();
    }
}
