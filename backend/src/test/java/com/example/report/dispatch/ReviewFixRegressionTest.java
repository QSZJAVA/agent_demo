package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPreview;
import com.example.report.permission.CurrentUser;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class ReviewFixRegressionTest {
    private static PreviewCommand command(String reportId) {
        return new PreviewCommand(null, "api", null, List.of(reportId), null, null, null);
    }

    @Test
    void planWithoutConversationSupersedesEarlierPlanAndOnlyLatestCanClaim() {
        DispatchHarness h = new DispatchHarness().put(SALES, candidate(SALES, "1", "SO1", "A", "item"));
        String previewId = h.previews.preview(USER1, null, command(SALES)).snapshot().preview().getId();
        var first = h.plans.create(USER1, null, previewId, List.of(), null);
        var second = h.plans.create(USER1, null, previewId, List.of(), null);
        assertEquals(DispatchPlan.EXPIRED, h.plans.getOwned(USER1, first.plan().getId()).plan().getStatus());
        assertFalse(h.plans.claimForExecution(USER1, first.plan(), LocalDateTime.now()).isPresent());
        assertTrue(h.plans.claimForExecution(USER1, second.plan(), LocalDateTime.now()).isPresent());
    }

    @Test
    void permissionRevocationBlocksPreviewPlanAndConversationHistory() {
        DispatchHarness h = new DispatchHarness().put(SALES, candidate(SALES, "1", "SO1", "A", "item"));
        String previewId = h.previews.preview(USER1, "c1", command(SALES)).snapshot().preview().getId();
        String planId = h.plans.create(USER1, "c1", previewId, List.of(), null).plan().getId();
        CurrentUser revoked = new CurrentUser("T001", USER1.userId(), "revoked", Set.of(), Set.of(), false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> h.previews.getOwned(revoked, previewId)).getCode());
        assertEquals(403, assertThrows(ApiException.class,
                () -> h.previews.pageOwned(revoked, previewId, 1, 50)).getCode());
        assertEquals(403, assertThrows(ApiException.class,
                () -> h.plans.pageOwned(revoked, planId, 1, 50)).getCode());
        assertEquals(403, assertThrows(ApiException.class,
                () -> h.previews.requireConversationReadable(revoked, "c1")).getCode());
    }

    @Test
    void currentGrantsAllowAdminAndExpandedUserToReadHistory() {
        DispatchHarness h = new DispatchHarness().put(SALES, candidate(SALES, "1", "SO1", "A", "item"));
        String previewId = h.previews.preview(USER1, "c1", command(SALES)).snapshot().preview().getId();
        CurrentUser expanded = new CurrentUser("T001", USER1.userId(), "user1", Set.of("A"),
                Set.of("report:sales", "report:receivable", "report:expense", "report:extra"), false);
        assertEquals(previewId, h.previews.getOwned(expanded, previewId).preview().getId());
        assertDoesNotThrow(() -> h.previews.requireReadable(ADMIN, h.previews.findOwned(USER1, previewId).orElseThrow()));
    }

    @Test
    void olderSlowPreviewCannotReplaceLaterRequest() throws Exception {
        DispatchHarness h = new DispatchHarness()
                .put(SALES, candidate(SALES, "1", "SO1", "A", "sales"))
                .put(EXPENSE, candidate(EXPENSE, "2", "EX1", "A", "expense"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(h.candidates.findCandidates(anyString(), anySet(), anyList(), anyInt(), anyList(),
                any(java.util.function.IntConsumer.class))).thenAnswer(call -> {
            List<com.example.report.catalog.CatalogEntry> reports = call.getArgument(2);
            if (SALES.equals(reports.get(0).reportId())) {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return h.data.get(reports.get(0).reportId());
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var old = pool.submit(() -> assertThrows(ApiException.class,
                    () -> h.previews.preview(USER1, "c1", command(SALES))));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            String latestId = h.previews.preview(USER1, "c1", command(EXPENSE)).snapshot().preview().getId();
            release.countDown();
            assertEquals(409, old.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(latestId, h.previews.latest(USER1, "c1").orElseThrow().getId());
            assertEquals(DispatchPreview.ACTIVE, h.previews.findOwned(USER1, latestId).orElseThrow().getStatus());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
