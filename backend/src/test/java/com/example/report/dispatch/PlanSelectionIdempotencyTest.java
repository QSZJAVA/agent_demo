package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;
import java.util.List;
import java.util.Optional;
import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlanSelectionIdempotencyTest {
    private final DispatchHarness h = new DispatchHarness()
            .put(SALES, candidate(SALES, "1", "DUP001", "A", "sale"))
            .put(EXPENSE, candidate(EXPENSE, "1", "dup001", "A", "expense"));

    private String preview(String conversation) {
        return h.previews.preview(USER1, conversation,
                new PreviewCommand(null, "api", null, List.of(SALES, EXPENSE), null, null))
                .snapshot().preview().getId();
    }

    private PlanService service(PlanRepository repository) {
        return new PlanService(h.previews, h.store.previews(), repository, h.versions, h.props,
                TransactionOperations.withoutTransaction(), h.quotas);
    }

    @Test void sameDocumentNumberExcludesOnlyTheSelectedRecord() {
        var plan = h.plans.create(USER1, "c1", preview("c1"), List.of(new RecordKey(SALES, "1")), null);
        assertEquals(1, plan.items().size());
        assertEquals(EXPENSE, plan.items().get(0).getReportId());
    }

    @Test void rejectsForeignRecord() {
        String id = preview("c1");
        assertThrows(ApiException.class, () -> h.plans.create(USER1, "c1", id, List.of(new RecordKey(SALES, "missing")), null));
        assertTrue(h.store.plans().pending("c1").isEmpty());
    }

    @Test void lockRecheckReplaysWinnerWithoutExpiringIt() {
        String id = preview("c1");
        var winner = h.plans.create(USER1, "c1", id, List.of(), "same");
        var repository = spy(h.store.plans());
        doReturn(Optional.empty(), Optional.empty(), Optional.of(winner.plan()))
                .when(repository).findByIdempotencyKey(USER1.tenantId(), "same");
        var replay = service(repository).create(USER1, "c1", id, List.of(), "same");
        assertTrue(replay.replayed());
        assertEquals(winner.plan().getId(), replay.plan().getId());
        assertEquals("PENDING", replay.plan().getStatus());
        verify(repository, never()).insert(any(), anyList());
    }

    @Test void crossConversationUniqueConflictReplaysCommittedWinner() {
        String first = preview("c1"), second = preview("c2");
        var repository = spy(h.store.plans());
        doAnswer(call -> {
            h.plans.create(USER1, "c1", first, List.of(), "race");
            throw new DuplicateKeyException("tenant idempotency constraint");
        }).when(repository).insert(any(), anyList());
        var replay = service(repository).create(USER1, "c2", second, List.of(), "race");
        assertTrue(replay.replayed());
        assertEquals("c1", replay.plan().getConversationId());
        assertEquals(1, h.store.plans().pending("c1").size());
        assertTrue(h.store.plans().pending("c2").isEmpty());
    }

    @Test void unrelatedUniqueConflictIsNotReportedAsSuccess() {
        String id = preview("c1");
        var repository = spy(h.store.plans());
        doThrow(new DuplicateKeyException("other constraint")).when(repository).insert(any(), anyList());
        assertThrows(DuplicateKeyException.class,
                () -> service(repository).create(USER1, "c1", id, List.of(), "new"));
    }
}
