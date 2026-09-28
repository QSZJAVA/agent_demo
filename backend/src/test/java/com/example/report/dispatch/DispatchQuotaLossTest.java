package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.config.ResourceQuotaService;
import com.example.report.conversation.ConversationService;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchQuotaLossTest {
    final DispatchHarness h = new DispatchHarness().put(SALES,
            candidate(SALES, "1", "SO1", "A", "first"), candidate(SALES, "2", "SO2", "A", "second"));
    final DispatchGateway gateway = mock(DispatchGateway.class);
    final ResourceQuotaService quotas = mock(ResourceQuotaService.class);
    final ResourceQuotaService.Permit permit = mock(ResourceQuotaService.Permit.class);
    final AtomicBoolean lost = new AtomicBoolean();
    final DispatchService service = new DispatchService(h.plans, h.previews, h.store.plans(), h.catalogService,
            h.candidates, h.versions, gateway, mock(AuditService.class), mock(ConversationService.class),
            mock(ChatMemory.class), TransactionOperations.withoutTransaction());

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "quotas", quotas);
        when(quotas.acquire(any(), anyString(), anyCollection())).thenReturn(permit);
        doAnswer(call -> { if (lost.get()) throw new ApiException(503, "lease lost"); return null; }).when(permit).requireValid();
    }
    @AfterEach void stop() { service.shutdownHeartbeats(); }

    String plan() {
        var preview = h.previews.preview(USER1, "c1", new PreviewCommand(null, "api", null, List.of(SALES), null, null, null)).snapshot();
        return h.plans.create(USER1, "c1", preview.preview().getId(), List.of(), null).plan().getId();
    }

    @Test void lossBeforeConfirmationLeavesPlanPendingWithoutSending() {
        String id = plan(); lost.set(true);
        assertEquals(503, assertThrows(ApiException.class, () -> service.confirm(USER1, id)).getCode());
        assertEquals(DispatchPlan.PENDING, h.plans.getOwned(USER1, id).plan().getStatus());
        verifyNoInteractions(gateway); verify(permit).close();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lossDuringGatewayPersistsOutcomeStopsNextSendAndCanReconcile(boolean unknown) {
        String id = plan();
        when(gateway.dispatch(any())).thenAnswer(call -> {
            lost.set(true);
            return unknown ? DispatchGateway.Outcome.fail("RESULT_UNKNOWN", "timeout") : DispatchGateway.Outcome.ok();
        });
        assertEquals(409, assertThrows(ApiException.class, () -> service.confirm(USER1, id)).getCode());
        var state = h.plans.getOwned(USER1, id);
        assertEquals(DispatchPlan.REVIEW_REQUIRED, state.plan().getStatus());
        assertEquals(unknown ? DispatchPlanItem.UNKNOWN : DispatchPlanItem.SUCCESS, state.items().get(0).getStatus());
        assertEquals(DispatchPlanItem.PENDING, state.items().get(1).getStatus());
        verify(gateway, times(1)).dispatch(any());

        // A fresh request gets a fresh permit; prior success is never sent again.
        when(quotas.acquire(any(), anyString(), anyCollection())).thenReturn(mock(ResourceQuotaService.Permit.class));
        String firstRequest = state.items().get(0).getExternalRequestId();
        when(gateway.lookup(anyString(), anyString())).thenAnswer(call -> new DispatchGateway.Lookup(
                firstRequest.equals(call.getArgument(1)) ? DispatchGateway.LookupStatus.SUCCESS : DispatchGateway.LookupStatus.NOT_FOUND, null, null));
        assertEquals(1, service.reconcile(USER1, id).successCount());
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.ok());
        assertEquals(2, service.retryFailed(USER1, id).successCount());
        verify(gateway, times(2)).dispatch(any());
        verify(gateway, times(1)).dispatch(argThat(request -> "1".equals(request.record().recordId())));
    }
}
