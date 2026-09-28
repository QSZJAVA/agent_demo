import com.example.report.support.DispatchHarness;
import com.example.report.dispatch.*;
import com.example.report.catalog.query.*;
import com.example.report.rule.*;
import com.example.report.conversation.ConversationService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionOperations;
import java.util.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Historical pre-K-fix probes, not acceptance criteria. The mock gateway does not implement the new
 * atomic guard contract. Use AtomicDispatchGuardTest, AtomicDispatchDatabaseTest and BoundedDryRunTest
 * to validate the fixed code. No real DB or gateway is accessed here. */
public class DispatchReviewKProbe {
    public static void main(String[] args) {
        staleQualification();
        unboundedDryRun();
    }

    static void staleQualification() {
        var first = candidate(SALES, "1", "SO1", "A", "first");
        var second = candidate(SALES, "2", "SO2", "A", "second");
        var h = new DispatchHarness().put(SALES, first, second);
        var preview = h.previews.preview(USER1, "review-k", new PreviewCommand(null, "api", null,
                List.of(SALES), null, null, null)).snapshot();
        var plan = h.plans.create(USER1, "review-k", preview.preview().getId(), List.of(), null);
        var gateway = mock(DispatchGateway.class);
        var sent = new ArrayList<String>();
        when(gateway.dispatch(any())).thenAnswer(call -> {
            DispatchGateway.DispatchRequest request = call.getArgument(0);
            sent.add(request.record().recordId());
            if ("1".equals(request.record().recordId())) {
                // Another writer moves record 2 to company B while record 1 is being sent.
                h.put(SALES, first, candidate(SALES, "2", "SO2", "B", "moved"));
            } else {
                assertFalse(USER1.companies().contains(h.data.get(SALES).get(1).companyCode()));
            }
            return DispatchGateway.Outcome.ok();
        });
        var service = new DispatchService(h.plans, h.previews, h.store.plans(), h.catalogService,
                h.candidates, h.versions, gateway, mock(AuditService.class), mock(ConversationService.class),
                mock(ChatMemory.class), TransactionOperations.withoutTransaction());
        try {
            assertEquals(2, service.confirm(USER1, plan.plan().getId()).successCount());
            assertEquals(List.of("1", "2"), sent);
            verify(h.candidates, times(1)).qualifiedPlanKeys(anyString(), anySet(), anyList(), anyMap());
            var jdbc = mock(NamedParameterJdbcTemplate.class);
            when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                assertFalse(sql.substring(sql.indexOf(" WHERE ")).contains("company_code"));
                return 1;
            });
            var config = new StandardQueryConfig("business_rows", "id", "company_code", "tenant_id",
                    "doc_no", "number", null, null, null, "dispatched", 0, 1, null, List.of(), List.of());
            assertTrue(new StandardReportAdapter(config, jdbc).markDispatched("T001", "2", java.time.LocalDateTime.now()));
            System.out.println("K1 reproduced: record 2 moved outside company scope after preflight, but was sent; writer SQL has no company predicate.");
        } finally { service.shutdownHeartbeats(); }
    }

    static void unboundedDryRun() {
        var h = new DispatchHarness();
        var adapter = mock(ReportQueryAdapter.class);
        var report = spy(h.catalog.get(SALES));
        doReturn(adapter).when(report).adapter();
        var rows = java.util.stream.IntStream.range(0, 10001).mapToObj(i -> new FactRow("" + i,
                "SO" + i, "A", "row", java.math.BigDecimal.TEN, null, Map.<String,Object>of("amount", 10L))).toList();
        when(adapter.pendingRows(anyString(), anySet())).thenReturn(rows);
        var engine = spy(new RuleEngine());
        var service = new DispatchCandidateService(h.rules, engine);
        var result = service.dryRun("T001", report, "A", "amount > 0", Set.of("A"));
        assertEquals(10001, result.hitCount());
        assertEquals(20, result.samples().size());
        verify(adapter).pendingRows("T001", Set.of("A"));
        verify(engine, times(10001)).matches(eq("amount > 0"), anyMap());
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(call -> { assertFalse(((String) call.getArgument(0)).contains(" LIMIT ")); return List.of(); });
        var config = new StandardQueryConfig("business_rows", "id", "company_code", "tenant_id", "doc_no",
                "number", null, null, null, "dispatched", 0, 1, null, List.of(), List.of());
        new StandardReportAdapter(config, jdbc).pendingRows("T001", Set.of("A"));
        System.out.println("K2 reproduced: dry-run loads every pending row and evaluates 10001 rows before returning 20 samples; standard SQL has no LIMIT.");
    }
}
