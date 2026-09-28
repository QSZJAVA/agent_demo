package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.config.ResourceQuotaService;
import com.example.report.entity.DispatchPlan;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanQuotaTest {
    private final DispatchHarness h = new DispatchHarness()
            .put(SALES, candidate(SALES, "1", "SO1", "A", "sale"));

    private String preview() {
        return h.previews.preview(USER1, "c1",
                new PreviewCommand(null, "api", null, List.of(SALES), null, null, null))
                .snapshot().preview().getId();
    }

    @Test void changingKeysCannotBypassQuotaAndRejectionDoesNotLoadItemsOrMutatePlans() {
        String id = preview();
        var original = h.plans.create(USER1, "c1", id, List.of(), "original");
        var previews = spy(h.store.previews());
        var plans = spy(h.store.plans());
        var tx = mock(TransactionOperations.class);
        var service = new PlanService(h.previews, previews, plans, h.versions, h.props, tx, h.quotas);
        when(h.quotas.acquire(eq(USER1), eq("plan-create"), eq(List.of(SALES))))
                .thenThrow(new ApiException(429, "请求过于频繁"));
        clearInvocations(h.quotas);
        for (String key : List.of("new-1", "new-2", "new-3")) {
            assertEquals(429, assertThrows(ApiException.class,
                    () -> service.create(USER1, "c1", id, List.of(), key)).getCode());
        }
        verify(h.quotas, times(3)).acquire(USER1, "plan-create", List.of(SALES));
        verify(previews, never()).items(anyString());
        verify(plans, never()).insert(any(), anyList());
        verify(plans, never()).transition(anyString(), anyString(), anyString(), any(), any());
        verifyNoInteractions(tx);
        assertEquals(DispatchPlan.PENDING, h.store.plans().find(original.plan().getId()).orElseThrow().getStatus());
    }

    @Test void knownIdempotencyKeyReplaysWithoutNewCreationQuota() {
        String id = preview();
        var original = h.plans.create(USER1, "c1", id, List.of(), "same");
        when(h.quotas.acquire(any(), anyString(), anyCollection())).thenThrow(new ApiException(429, "quota"));
        clearInvocations(h.quotas);
        var replay = h.plans.create(USER1, "c1", id, List.of(), "same");
        assertTrue(replay.replayed());
        assertEquals(original.plan().getId(), replay.plan().getId());
        verifyNoInteractions(h.quotas);
    }

    @Test void permitClosesAfterBothSuccessfulCreationAndValidationFailure() {
        String id = preview();
        var permit = mock(ResourceQuotaService.Permit.class);
        when(h.quotas.acquire(eq(USER1), eq("plan-create"), eq(List.of(SALES)))).thenReturn(permit);
        h.plans.create(USER1, "c1", id, List.of(), null);
        verify(permit).close();
        assertThrows(ApiException.class, () -> h.plans.create(USER1, "c1", id, List.of("missing"), null));
        verify(permit, times(2)).close();
    }

    @Test void previewIsPinnedBeforeQuotaSoNewerReportsCannotEscapeTheirOwnQuota() {
        String id = preview();
        h.put(EXPENSE, candidate(EXPENSE, "1", "EX1", "A", "expense"));
        when(h.quotas.acquire(eq(USER1), eq("plan-create"), eq(List.of(SALES)))).thenAnswer(call -> {
            h.previews.preview(USER1, "c1",
                    new PreviewCommand(null, "api", null, List.of(EXPENSE), null, null, null));
            return null;
        });
        assertThrows(ApiException.class, () -> h.plans.create(USER1, "c1", null, List.of(), null));
        assertTrue(h.store.plans().pending("c1").isEmpty());
        assertEquals("SUPERSEDED", h.store.previews().find(id).orElseThrow().getStatus());
    }

    @Test void lossWhileWaitingForCreationLockDoesNotExpireTheExistingPlan() {
        String id = preview();
        var original = h.plans.create(USER1, "c1", id, List.of(), "original");
        var permit = mock(ResourceQuotaService.Permit.class);
        when(h.quotas.acquire(any(), anyString(), anyCollection())).thenReturn(permit);
        doNothing().doThrow(new ApiException(503, "lease lost")).when(permit).requireValid();
        assertThrows(ApiException.class, () -> h.plans.create(USER1, "c1", id, List.of(), "new"));
        assertEquals(List.of(original.plan().getId()), h.store.plans().pending("c1").stream().map(DispatchPlan::getId).toList());
        verify(permit).close();
    }
}
