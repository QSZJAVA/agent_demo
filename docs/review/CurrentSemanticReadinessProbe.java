package com.example.report.semantic;

import com.example.report.agent.AgentChatService;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.dispatch.PreviewService;
import com.example.report.operations.SensitiveData;
import com.example.report.permission.PermissionService;
import com.example.report.web.AgentController;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.PrintWriter;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.mockito.Mockito.*;

/** Regression verification for the reviewed defects, updated after their fixes.
 * Reuses the existing integration harness's UUID database, mock model and cleanup.
 * No confirmation or actual dispatch is performed.
 */
public class CurrentSemanticReadinessProbe extends SemanticIntegrationTest {
    @Test void expiredPreviewRetainsExclusions() {
        String id = conversation();
        turn(id, "A公司销售报表的");
        turn(id, "排除SO2026002");
        var before = store.read(user(), id);
        assertEquals(1, before.getExcludedRecords().size());
        jdbc.update("UPDATE dispatch_preview SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", before.getPreviewId());

        var events = turn(id, "剩下的帮我派单吧");
        assertTrue(events.stream().anyMatch(e -> "plan".equals(e.event())), text(events));
        var after = store.read(user(), id);
        var plan = plans.getOwned(user(), after.getPlanId());
        assertNotEquals(before.getPreviewId(), after.getPreviewId());
        assertEquals(before.getExcludedRecords(),after.getExcludedRecords());
        assertTrue(plan.items().stream().noneMatch(i -> "SO2026002".equals(i.getDocNo())));
        assertEquals("PENDING", plan.plan().getStatus());
        assertTrue(plan.items().stream().allMatch(i -> i.getAttemptCount() == null || i.getAttemptCount() == 0));
        System.out.println("VERIFIED expiry: explicit exclusion SO2026002 stays excluded from the pending plan");
    }

    @Test void controllerPreservesNumericDocument() {
        var service = mock(AgentChatService.class);
        var permissions = new PermissionService();
        var controller = new AgentController(service, permissions, mock(PreviewService.class), mock(ReportCatalogService.class));
        var request = new AgentController.ChatRequest();
        request.setConversationId("synthetic-conversation");
        // A synthetic 18-digit document number, not a real personal identifier.
        String document = "900000000000000001";
        request.setMessage("排除单据" + document);
        request.setExcludeDocNos(List.of());
        request.setExcludedRecords(List.of());
        controller.chat("user1", request);
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service).chat(eq(permissions.resolve("user1")), eq("synthetic-conversation"), captured.capture(), eq(List.of()), isNull(), eq(List.of()));
        assertEquals("排除单据"+document, captured.getValue());
        var protectedInput=SensitiveData.modelText(captured.getValue());
        assertFalse(protectedInput.text().contains(document));
        assertEquals(captured.getValue(),protectedInput.restore(protectedInput.text()));
        // Structured docNo fields remain intact; only the natural-language input loses identity.
        assertEquals(document, SensitiveData.value(java.util.Map.of("docNo", document)).get("docNo").asText());
        System.out.println("VERIFIED input masking: local entity identity is preserved and model input is tokenized");
    }

    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request().selectors(
                selectMethod(CurrentSemanticReadinessProbe.class, "expiredPreviewRetainsExclusions"),
                selectMethod(CurrentSemanticReadinessProbe.class, "controllerPreservesNumericDocument")).build(), listener);
        listener.getSummary().printTo(new PrintWriter(System.out, true));
        listener.getSummary().printFailuresTo(new PrintWriter(System.out, true));
        if (listener.getSummary().getTestsSucceededCount() != 2 || listener.getSummary().getTotalFailureCount() != 0)
            throw new AssertionError("Both defect reproductions must complete");
    }
}
