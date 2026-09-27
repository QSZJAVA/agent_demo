package com.example.report.web;

import com.example.report.agent.ConversationCards;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.ApiException;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.*;
import com.example.report.permission.PermissionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.example.report.support.TestCatalog.USER1;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewJobRecoveryTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final PreviewJobService jobs = mock(PreviewJobService.class);
    private final DispatchController controller = new DispatchController(permissions, mock(PreviewService.class),
            mock(PlanService.class), mock(DispatchService.class), mock(DispatchTraceService.class),
            mock(CardStateService.class), conversations, mock(ConversationCards.class),
            mock(ReportCatalogService.class), jobs);

    @Test
    void rejectsForeignConversationBeforeLookingUpTasks() {
        when(permissions.resolve("user1")).thenReturn(USER1);
        when(conversations.getOwned(USER1, "foreign")).thenThrow(ApiException.notFound("会话不存在"));
        assertEquals(404, assertThrows(ApiException.class,
                () -> controller.recoverPreviewJob("user1", "foreign")).getCode());
        verifyNoInteractions(jobs);
    }

    @Test
    void completedTaskCanBeRecoveredAfterItsEventWasLost() {
        when(permissions.resolve("user1")).thenReturn(USER1);
        var now = LocalDateTime.now();
        var completed = new PreviewJobService.Job("job1", "SUCCEEDED", "DONE", 100, null, "preview1", now, now);
        when(jobs.latestForConversation(USER1, "conv1")).thenReturn(completed);
        assertEquals(completed, controller.recoverPreviewJob("user1", "conv1").data());
        var order = inOrder(conversations, jobs);
        order.verify(conversations).getOwned(USER1, "conv1");
        order.verify(jobs).latestForConversation(USER1, "conv1");
    }

    @Test
    void conversationWithoutTaskReturnsEmptySuccessfulResult() {
        when(permissions.resolve("user1")).thenReturn(USER1);
        var result = controller.recoverPreviewJob("user1", "conv1");
        assertEquals(0, result.code());
        assertNull(result.data());
        verify(conversations).getOwned(USER1, "conv1");
    }
}
