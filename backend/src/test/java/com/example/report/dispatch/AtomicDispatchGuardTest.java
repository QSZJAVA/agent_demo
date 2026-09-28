package com.example.report.dispatch;

import com.example.report.catalog.query.*;
import com.example.report.entity.DispatchRule;
import com.example.report.rule.*;
import com.example.report.support.DispatchHarness;
import com.example.report.conversation.ConversationService;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.support.*;
import java.math.BigDecimal;
import java.util.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AtomicDispatchGuardTest {
    final DispatchHarness h = new DispatchHarness();
    final NamedParameterJdbcTemplate business = mock(NamedParameterJdbcTemplate.class);
    final JdbcTemplate journal = mock(JdbcTemplate.class);
    final RuleCache rules = mock(RuleCache.class);
    final Map<String, FactRow> rows = new HashMap<>();
    final List<String> updated = new ArrayList<>();
    MockDispatchGateway gateway;
    DispatchService service;
    Runnable afterFirst = () -> {};

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var physical = mock(JdbcTemplate.class);
        when(business.getJdbcTemplate()).thenReturn(physical);
        when(physical.execute(any(org.springframework.jdbc.core.ConnectionCallback.class))).thenReturn(true);
        var config = new StandardQueryConfig("business_rows", "id", "company_code", "tenant_id", "doc_no", "number",
                null, "amount", null, "dispatched", 0, 1, null,
                List.of(new StandardQueryConfig.FieldSpec("amount", "amount", "decimal", "amount")), List.of());
        var report = spy(h.catalog.get(SALES));
        doReturn(new StandardReportAdapter(config, business)).when(report).adapter();
        h.catalog.replace(report);
        var rule = new DispatchRule();
        rule.setId(1L); rule.setVersion(1); rule.setExpression("amount > 20");
        when(rules.find("T001", SALES, "A")).thenReturn(Optional.of(rule));
        gateway = new MockDispatchGateway(journal, rules, new RuleEngine());
        when(business.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            assertTrue(sql.endsWith(" FOR UPDATE"));
            assertTrue(sql.contains("`company_code` = :company"));
            assertTrue(sql.contains("`tenant_id` = :tenantId"));
            assertTrue(sql.contains("`dispatched` = :pending"));
            SqlParameterSource params = call.getArgument(1);
            FactRow row = rows.get(params.getValue("id"));
            return row != null && row.companyCode().equals(params.getValue("company")) ? List.of(row) : List.of();
        });
        when(business.update(anyString(), any(SqlParameterSource.class))).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            String id = (String) ((SqlParameterSource) call.getArgument(1)).getValue("id");
            updated.add(id);
            if ("1".equals(id)) afterFirst.run();
            return 1;
        });
        service = new DispatchService(h.plans, h.previews, h.store.plans(), h.catalogService, h.candidates, h.versions,
                gateway, mock(AuditService.class), mock(ConversationService.class), mock(ChatMemory.class),
                TransactionOperations.withoutTransaction());
        // Unit test transaction marker only; SQL locking itself is verified by an opt-in database test.
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); service.shutdownHeartbeats(); }

    FactRow row(String id, String company, int amount) {
        return new FactRow(id, "SO" + id, company, "item", BigDecimal.valueOf(amount), null, Map.of("amount", amount));
    }

    String plan() {
        h.put(SALES, candidate(SALES, "1", "SO1", "A", "first"), candidate(SALES, "2", "SO2", "A", "second"));
        rows.put("1", row("1", "A", 2000)); rows.put("2", row("2", "A", 2000));
        var p = h.previews.preview(USER1, "atomic", new PreviewCommand(null, "api", null, List.of(SALES), null, null, null)).snapshot();
        return h.plans.create(USER1, "atomic", p.preview().getId(), List.of(), null).plan().getId();
    }

    @Test void companyChangeAfterBatchPreflightCannotBeWritten() {
        String id = plan();
        afterFirst = () -> rows.put("2", row("2", "B", 2000));
        var result = service.confirm(USER1, id);
        assertEquals(1, result.successCount()); assertEquals(1, result.failedCount());
        assertEquals(List.of("1"), updated);
        assertEquals("RECORD_CHANGED", result.failed().get(0).errorCode());
    }

    @Test void ruleFactChangeBlocksConfirmationAndRetryUntilFreshFactsQualify() {
        String id = plan();
        afterFirst = () -> rows.put("2", row("2", "A", 1));
        assertEquals(1, service.confirm(USER1, id).successCount());
        assertEquals(1, service.retryFailed(USER1, id).failedCount());
        assertEquals(List.of("1"), updated);
        rows.put("2", row("2", "A", 2000));
        assertEquals(2, service.retryFailed(USER1, id).successCount());
        assertEquals(List.of("1", "2"), updated);
    }

    @Test void manualRequestSkipsRulesButStillChecksCompany() {
        rows.put("1", row("1", "A", 1));
        var record = candidate(SALES, "1", "SO1", "A", "manual");
        assertTrue(gateway.dispatch(new DispatchGateway.DispatchRequest("T001", "m1", h.catalog.get(SALES), record, false)).success());
        rows.put("1", row("1", "B", 1));
        assertFalse(gateway.dispatch(new DispatchGateway.DispatchRequest("T001", "m2", h.catalog.get(SALES), record, false)).success());
        assertEquals(List.of("1"), updated); verifyNoInteractions(rules);
    }

    @Test void changedRuleVersionIsRejectedAndMissingTransactionCannotWrite() {
        rows.put("1", row("1", "A", 2000));
        var rule = new DispatchRule(); rule.setId(1L); rule.setVersion(2); rule.setExpression("amount > 20");
        when(rules.find("T001", SALES, "A")).thenReturn(Optional.of(rule));
        var request = new DispatchGateway.DispatchRequest("T001", "v1", h.catalog.get(SALES), candidate(SALES, "1", "SO1", "A", "item"), true);
        assertFalse(gateway.dispatch(request).success());
        TransactionSynchronizationManager.clear();
        assertThrows(IllegalStateException.class, () -> gateway.dispatch(request));
        assertTrue(updated.isEmpty());
    }
}
