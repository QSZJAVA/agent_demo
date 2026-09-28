package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.config.ResourceQuotaService;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.function.IntConsumer;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewQuotaLossTest {
    @Test void lossAtScanBoundaryCannotActivateANewPreviewOrInvalidateThePreviousOne() {
        var h = new DispatchHarness().put(SALES, candidate(SALES, "1", "SO1", "A", "first"));
        var command = new PreviewCommand(null, "api", null, List.of(SALES), null, null, null);
        String previous = h.previews.preview(USER1, "c1", command).snapshot().preview().getId();
        var quotas = mock(ResourceQuotaService.class);
        var permit = mock(ResourceQuotaService.Permit.class);
        when(quotas.acquire(any(), anyString(), anyCollection())).thenReturn(permit);
        ReflectionTestUtils.setField(h.previews, "quotas", quotas);
        doNothing().doThrow(new ApiException(503, "lease lost")).when(permit).requireValid();
        when(h.candidates.findCandidates(anyString(), anySet(), anyList(), anyInt(), anyList(), any(IntConsumer.class)))
                .thenAnswer(call -> { call.<IntConsumer>getArgument(5).accept(500); return List.of(); });
        assertThrows(ApiException.class, () -> h.previews.preview(USER1, "c1", command));
        assertEquals(previous, h.previews.latest(USER1, "c1").orElseThrow().getId());
        assertEquals("ACTIVE", h.store.previews().find(previous).orElseThrow().getStatus());
        verify(permit).close();
    }
}
