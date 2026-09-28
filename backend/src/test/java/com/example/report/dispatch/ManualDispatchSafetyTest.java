package com.example.report.dispatch;

import com.example.report.catalog.query.FactRow;
import com.example.report.catalog.query.ReportQueryAdapter;
import com.example.report.common.ApiException;
import com.example.report.conversation.ConversationService;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static com.example.report.support.TestCatalog.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualDispatchSafetyTest {
    DispatchHarness h;
    DispatchService service;
    DispatchGateway gateway;
    ReportQueryAdapter adapter;

    @BeforeEach void setup() {
        h = new DispatchHarness();
        adapter = mock(ReportQueryAdapter.class);
        when(adapter.rowsByIds(anyString(), anyCollection())).thenAnswer(call -> {
            java.util.Collection<String> ids = call.getArgument(1);
            return ids.stream().map(id -> new FactRow(id, "SO" + id, "A", "manual", BigDecimal.TEN, null, Map.of())).toList();
        });
        when(adapter.pendingRowsByIds(anyString(), anyCollection())).thenAnswer(call -> adapter.rowsByIds(call.getArgument(0), call.getArgument(1)));
        var report = spy(h.catalog.get(SALES));
        doReturn(adapter).when(report).adapter();
        h.catalog.replace(report);
        gateway = mock(DispatchGateway.class);
        doReturn(DispatchGateway.Outcome.ok()).when(gateway).dispatch(any());
        service = new DispatchService(h.plans, h.previews, h.store.plans(), h.catalogService, h.candidates, h.versions,
                gateway, mock(AuditService.class), mock(ConversationService.class), mock(ChatMemory.class),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }
    @AfterEach void close() { service.shutdownHeartbeats(); }

    @Test void unknownIsPersistedAndRepeatedOrOverlappingBatchDoesNotResend() {
        when(gateway.dispatch(any())).thenThrow(new IllegalStateException("connection lost"));
        var first = service.dispatchDirect(USER1, SALES, List.of("1"));
        assertEquals(1, first.reviewCount());
        String id = first.plans().get(0).planId();
        assertEquals("REVIEW_REQUIRED", first.plans().get(0).status());
        assertNotNull(h.store.plans().items(id).get(0).getExternalRequestId());
        var again = service.dispatchDirect(USER1, SALES, List.of("1", "2"));
        assertEquals(id, again.plans().get(0).planId());
        assertEquals(2, again.reviewCount());
        verify(gateway, times(2)).dispatch(any());
        assertEquals(2, service.manualPlans(USER1, SALES, 1).size());
        assertTrue(service.manualPlans(USER2, SALES, 1).isEmpty());
    }

    @Test void reconciliationAndExplicitRetryReuseOriginalRequestNumber() {
        when(gateway.dispatch(any())).thenThrow(new IllegalStateException("timeout"));
        String id = service.dispatchDirect(USER1, SALES, List.of("1")).plans().get(0).planId();
        String requestId = h.store.plans().items(id).get(0).getExternalRequestId();
        when(gateway.lookup(anyString(), eq(requestId))).thenReturn(new DispatchGateway.Lookup(DispatchGateway.LookupStatus.UNKNOWN, null, null));
        assertThrows(ApiException.class, () -> service.reconcile(USER1, id));
        assertThrows(ApiException.class, () -> service.retryFailed(USER1, id));
        when(gateway.lookup(anyString(), eq(requestId))).thenReturn(new DispatchGateway.Lookup(DispatchGateway.LookupStatus.NOT_FOUND, null, null));
        assertEquals(1, service.reconcile(USER1, id).retryableCount());
        // Manual dispatch remains independent of automatic matching rules.
        h.ruleVersion.set("rules-v2");
        doReturn(DispatchGateway.Outcome.ok()).when(gateway).dispatch(any());
        assertEquals(1, service.retryFailed(USER1, id).successCount());
        assertEquals(1, service.dispatchDirect(USER1, SALES, List.of("1")).successCount());
        verify(gateway, times(2)).dispatch(argThat(r -> requestId.equals(r.externalRequestId())));
        verify(h.candidates, never()).qualifiedPlanKeys(anyString(), anySet(), anyList(), anyMap());
    }

    @Test void anotherUserCannotReplayOrExecuteAnExistingManualRecord() {
        service.dispatchDirect(USER1, SALES, List.of("1"));
        assertThrows(ApiException.class, () -> service.dispatchDirect(ADMIN, SALES, List.of("1")));
        verify(gateway, times(1)).dispatch(any());
    }

    @Test void failedPreflightRemainsRecoverableAndExpiredUnsentDraftCanBeReplaced() {
        doThrow(new IllegalStateException("offline")).when(adapter).pendingRowsByIds(anyString(), anyCollection());
        String id = service.dispatchDirect(USER1, SALES, List.of("1")).plans().get(0).planId();
        assertEquals("PENDING", h.store.plans().find(id).orElseThrow().getStatus());
        verifyNoInteractions(gateway);
        h.store.updatePlan(id, p -> p.setExpiresAt(LocalDateTime.now().minusSeconds(1)));
        doAnswer(call -> adapter.rowsByIds(call.getArgument(0), call.getArgument(1)))
                .when(adapter).pendingRowsByIds(anyString(), anyCollection());
        var result = service.dispatchDirect(USER1, SALES, List.of("1"));
        assertNotEquals(id, result.plans().get(0).planId());
        assertEquals(1, result.successCount());
    }

    @Test void changedCompanyIsNotSentAndBatchLimitIsEnforcedBeforeQuery() {
        doReturn(List.of(new FactRow("1", "SO1", "B", "moved", BigDecimal.TEN, null, Map.of())))
                .when(adapter).pendingRowsByIds(anyString(), anyCollection());
        assertEquals(0, service.dispatchDirect(USER1, SALES, List.of("1")).successCount());
        verifyNoInteractions(gateway);
        doAnswer(call -> adapter.rowsByIds(call.getArgument(0), call.getArgument(1)))
                .when(adapter).pendingRowsByIds(anyString(), anyCollection());
        assertEquals(1, service.dispatchDirect(USER1, SALES, List.of("1")).successCount());
        clearInvocations(adapter);
        assertThrows(ApiException.class, () -> service.dispatchDirect(USER1, SALES,
                java.util.stream.IntStream.range(0, 51).mapToObj(String::valueOf).toList()));
        verifyNoInteractions(adapter);
    }

    @Test void manualSnapshotCannotBeUsedToMintAnotherPlanOrReserveItsKey() {
        var plan = h.plans.manual(USER1, h.catalog.get(SALES), "1");
        assertThrows(ApiException.class, () -> h.plans.create(USER1, null, plan.plan().getPreviewId(), List.of(), null));
        assertThrows(ApiException.class, () -> h.plans.create(USER1, null, plan.plan().getPreviewId(), List.of(),
                plan.plan().getIdempotencyKey()));
        assertThrows(ApiException.class, () -> h.plans.create(USER1, null, plan.plan().getPreviewId(), List.of(),
                plan.plan().getIdempotencyKey().toUpperCase(java.util.Locale.ROOT)));
        assertEquals(plan.plan().getId(), h.plans.manual(USER1, h.catalog.get(SALES), "1").plan().getId());
        verifyNoInteractions(gateway);
    }

    @Test void competingManualCreationReturnsTheWinningPlan() {
        var repository = spy(h.store.plans());
        doAnswer(call -> {
            h.plans.manual(USER1, h.catalog.get(SALES), "1");
            throw new org.springframework.dao.DuplicateKeyException("same manual record");
        }).when(repository).insert(any(), anyList());
        var competing = new PlanService(h.previews, h.store.previews(), repository, h.versions, h.props,
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
        var result = competing.manual(USER1, h.catalog.get(SALES), "1");
        assertTrue(result.replayed());
        assertEquals(result.plan().getId(), h.plans.manual(USER1, h.catalog.get(SALES), "1").plan().getId());
    }
}
