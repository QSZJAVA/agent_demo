package com.example.report.dispatch;

import com.example.report.agent.ConversationCards;
import com.example.report.config.ResourceQuotaService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;
import static com.example.report.support.TestCatalog.USER1;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewJobLifecycleTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PreviewJobService service = spy(new PreviewJobService(jdbc, mock(PreviewService.class),
            mock(ConversationCards.class), mock(ResourceQuotaService.class)));
    final ResourceQuotaService.Permit permit = mock(ResourceQuotaService.Permit.class);
    final FutureTask<Void> future = new FutureTask<>(() -> null);
    ThreadPoolExecutor workers;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var now = LocalDateTime.now();
        doReturn(new PreviewJobService.Job("job", "RUNNING", "QUERYING", 0, null, null, now, now)).when(service).get(USER1,"job");
        ((Map<String,Future<?>>)ReflectionTestUtils.getField(service,"futures")).put("job",future);
        ((Map<String,ResourceQuotaService.Permit>)ReflectionTestUtils.getField(service,"permits")).put("job",permit);
        workers = (ThreadPoolExecutor)ReflectionTestUtils.getField(service,"workers");
        workers.getQueue().add(future);
    }
    @AfterEach void stop() { service.shutdown(); }

    @Test void timeoutDoesNotCancelWhenActivationWonTheDatabaseTransition() {
        when(jdbc.update(anyString(),eq("job"))).thenReturn(0);
        ReflectionTestUtils.invokeMethod(service,"timeout",USER1,"job");
        assertFalse(future.isCancelled()); assertTrue(workers.getQueue().contains(future));
        verify(permit,never()).close();
    }

    @Test void successfulTimeoutRemovesCancelledTaskFromExecutorQueue() {
        when(jdbc.update(anyString(),eq("job"))).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service,"timeout",USER1,"job");
        assertTrue(future.isCancelled()); assertFalse(workers.getQueue().contains(future));
        verify(permit).close();
    }

    @Test void explicitCancelAlsoReclaimsQueuedTaskCapacity() {
        when(jdbc.update(anyString(),eq("job"),eq(USER1.tenantId()),eq(USER1.userId()))).thenReturn(1);
        service.cancel(USER1,"job");
        assertTrue(future.isCancelled()); assertTrue(workers.getQueue().isEmpty());
        verify(permit).close();
    }

    @Test void rejectedSubmissionReleasesPermitEvenWhenFailureStateCannotBeWritten() {
        workers.shutdown();
        var quotas = (ResourceQuotaService)ReflectionTestUtils.getField(service,"quotas");
        when(quotas.acquire(any(),anyString(),anyCollection())).thenReturn(permit);
        doThrow(new IllegalStateException("state write unavailable")).when(jdbc)
                .update(startsWith("UPDATE dispatch_preview_job SET status=?"),any(),any(),any(),isNull(),any());
        assertThrows(IllegalStateException.class, () -> service.submit(USER1,null,
                new PreviewCommand(null,"api",null,java.util.List.of(),null,null,null)));
        verify(permit).close();
    }
}
