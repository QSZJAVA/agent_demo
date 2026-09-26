package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPreview;
import com.example.report.support.DispatchHarness;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import java.time.LocalDateTime;
import java.util.List;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DispatchSafetyTest {
    private final DispatchHarness h = new DispatchHarness().put(SALES, candidate(SALES, "1", "SO1", "A", "item"));
    private final DispatchGateway gateway = mock(DispatchGateway.class);

    private DispatchService service(PlanRepository repository) {
        return new DispatchService(h.plans, h.previews, repository, h.catalogService,
                h.candidates, h.versions, gateway, mock(AuditService.class), mock(ConversationService.class), mock(ChatMemory.class));
    }

    private PlanSnapshot plan() {
        var preview = h.previews.preview(USER1, "c1", new PreviewCommand(null, "api", null, List.of(SALES), null, null, null)).snapshot();
        return h.plans.create(USER1, "c1", preview.preview().getId(), List.of(), null);
    }

    @Test void expiredSourcePreviewBlocksConfirmationEvenWhenPlanHasNotExpired() {
        var plan = plan();
        h.store.updatePreview(plan.plan().getPreviewId(), p -> p.setExpiresAt(LocalDateTime.now().minusSeconds(1)));
        assertThrows(ApiException.class, () -> service(h.store.plans()).confirm(USER1, plan.plan().getId()));
        assertEquals(DispatchPlan.EXPIRED, h.plans.getOwned(USER1, plan.plan().getId()).plan().getStatus());
        verifyNoInteractions(gateway);
    }

    @Test void supersededSourcePreviewBlocksConfirmation() {
        var plan = plan();
        h.store.updatePreview(plan.plan().getPreviewId(), p -> p.setStatus(DispatchPreview.SUPERSEDED));
        assertThrows(ApiException.class, () -> service(h.store.plans()).confirm(USER1, plan.plan().getId()));
        verifyNoInteractions(gateway);
    }

    @Test void planCannotOutliveItsSourcePreview() {
        h.props.getPreview().setTtlMinutes(1);
        h.props.getPlan().setTtlMinutes(10);
        var plan = plan();
        assertEquals(h.previews.findOwned(USER1, plan.plan().getPreviewId()).orElseThrow().getExpiresAt(), plan.plan().getExpiresAt());
    }

    @Test void failedRevalidationCanBeRetriedWithoutAnyPriorDispatch() {
        var plan = plan();
        when(h.candidates.findCandidates(anyString(), anySet(), anyList()))
                .thenThrow(new RuntimeException("database unavailable"))
                .thenReturn(List.of(candidate(SALES, "1", "SO1", "A", "item")));
        var service = service(h.store.plans());
        assertEquals(503, assertThrows(ApiException.class, () -> service.confirm(USER1, plan.plan().getId())).getCode());
        assertEquals(DispatchPlan.PENDING, h.plans.getOwned(USER1, plan.plan().getId()).plan().getStatus());
        verifyNoInteractions(gateway);
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.ok());
        assertEquals(1, service.confirm(USER1, plan.plan().getId()).successCount());
        verify(gateway, times(1)).dispatch(any());
    }

    @Test void savedResultFailureRequiresReviewAndNeverResends() {
        var plan = plan();
        PlanRepository repository = spy(h.store.plans());
        doThrow(new RuntimeException("write failed")).when(repository).updateItem(any());
        when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.ok());
        var service = service(repository);
        assertEquals(409, assertThrows(ApiException.class, () -> service.confirm(USER1, plan.plan().getId())).getCode());
        assertEquals(DispatchPlan.REVIEW_REQUIRED, h.plans.getOwned(USER1, plan.plan().getId()).plan().getStatus());
        assertEquals(409, assertThrows(ApiException.class, () -> service.confirm(USER1, plan.plan().getId())).getCode());
        assertThrows(ApiException.class, () -> service.cancel(USER1, plan.plan().getId()));
        assertThrows(ApiException.class, () -> h.plans.create(USER1, "c1", plan.plan().getPreviewId(), List.of(), null));
        verify(gateway, times(1)).dispatch(any());
    }

    @Test void manualDispatchRespectsDispatchSwitchForIdsAndLegacyCodes() {
        h.catalog.replace(TestCatalog.with(h.catalog.get(SALES), 2, "PUBLISHED", false));
        var service = service(h.store.plans());
        assertThrows(ApiException.class, () -> service.dispatchDirect(USER1, SALES, List.of("1")));
        assertThrows(ApiException.class, () -> service.dispatchDirect(USER1, "sales", List.of("1")));
        verifyNoInteractions(gateway);
    }
}
