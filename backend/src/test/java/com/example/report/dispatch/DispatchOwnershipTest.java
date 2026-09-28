package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.memory.ChatMemory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchOwnershipTest {
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "执行交错未到达预期阶段");
    }

    private static DispatchService service(DispatchHarness h, PlanService plans, PlanRepository repository,
                                           DispatchGateway gateway) {
        return new DispatchService(plans, h.previews, repository, h.catalogService, h.candidates, h.versions,
                gateway, mock(AuditService.class), mock(ConversationService.class), mock(ChatMemory.class),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revokedOwnerCannotBorrowNextRetryVersionAfterClaimReturns(boolean oldIsRetry) throws Exception {
        var record = candidate(SALES, "1", "SO1", "A", "item");
        var h = new DispatchHarness().put(SALES, record);
        var preview = h.previews.preview(USER1, "c1",
                new PreviewCommand(null, "api", null, List.of(SALES), null, null, null)).snapshot();
        String id = h.plans.create(USER1, "c1", preview.preview().getId(), List.of(), null).plan().getId();
        var gateway = mock(DispatchGateway.class);
        var current = service(h, h.plans, h.store.plans(), gateway);
        PlanService oldPlans = spy(h.plans);
        PlanRepository oldRepository = spy(h.store.plans());
        var old = service(h, oldPlans, oldRepository, gateway);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch nextChecking = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        AtomicLong oldVersion = new AtomicLong();
        var pool = Executors.newFixedThreadPool(2);
        try {
            if (oldIsRetry) {
                when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.fail("REJECTED", "failed"));
                assertEquals(1, current.confirm(USER1, id).failedCount());
                clearInvocations(gateway);
                doAnswer(call -> {
                    Optional<Long> version = h.store.plans().claimRetry(id, call.getArgument(1));
                    oldVersion.set(version.orElseThrow());
                    claimed.countDown();
                    await(releaseOld);
                    return version;
                }).when(oldRepository).claimRetry(eq(id), any());
            } else {
                doAnswer(call -> {
                    Optional<Long> version = h.plans.claimForExecution(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                    oldVersion.set(version.orElseThrow());
                    claimed.countDown();
                    await(releaseOld);
                    return version;
                }).when(oldPlans).claimForExecution(eq(USER1), any(), any());
            }
            when(gateway.dispatch(any())).thenReturn(DispatchGateway.Outcome.ok());
            when(gateway.lookup(anyString(), anyString())).thenReturn(
                    new DispatchGateway.Lookup(DispatchGateway.LookupStatus.NOT_FOUND, null, "absent"));
            var stale = pool.submit(() -> assertThrows(ApiException.class, () -> {
                if (oldIsRetry) old.retryFailed(USER1, id); else old.confirm(USER1, id);
            }));
            await(claimed);
            h.store.updatePlan(id, row -> row.setUpdatedAt(LocalDateTime.now().minusMinutes(6)));
            new DispatchRecoveryService(h.store.plans()).recover();
            current.reconcile(USER1, id);
            doAnswer(call -> {
                // 只暂停新执行者；旧执行者恢复时必须仍使用已捕获的旧版本。
                if (Thread.currentThread().getName().equals("next-execution")) {
                    nextChecking.countDown();
                    await(releaseNext);
                }
                return Set.of(record.key());
            }).when(h.candidates).qualifiedPlanKeys(anyString(), anySet(), anyList(), anyMap());
            var next = pool.submit(() -> {
                Thread.currentThread().setName("next-execution");
                return current.retryFailed(USER1, id);
            });
            await(nextChecking);
            long nextVersion = h.store.plans().find(id).orElseThrow().getExecutionVersion();
            assertEquals(oldVersion.get() + 2, nextVersion);
            releaseOld.countDown();
            assertEquals(409, stale.get(10, TimeUnit.SECONDS).getCode());
            verify(gateway, never()).dispatch(any());
            assertTrue(h.store.plans().isExecuting(id, nextVersion), "旧线程不得改变新轮次状态");
            releaseNext.countDown();
            assertEquals(1, next.get(10, TimeUnit.SECONDS).successCount());
            verify(gateway, times(1)).dispatch(any());
            assertEquals(DispatchPlan.EXECUTED, h.store.plans().find(id).orElseThrow().getStatus());
        } finally {
            releaseOld.countDown();
            releaseNext.countDown();
            pool.shutdownNow();
            old.shutdownHeartbeats();
            current.shutdownHeartbeats();
        }
    }
}
