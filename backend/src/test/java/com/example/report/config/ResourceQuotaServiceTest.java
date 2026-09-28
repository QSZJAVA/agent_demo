package com.example.report.config;

import com.example.report.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.*;
import static com.example.report.support.TestCatalog.USER1;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceQuotaServiceTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final QuotaLeaseStore store = mock(QuotaLeaseStore.class);
    final ResourceQuotaService service = new ResourceQuotaService(redis, store);

    @BeforeEach @SuppressWarnings("unchecked") void rates() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);
    }
    @AfterEach void shutdown() { service.shutdown(); }

    @Test @SuppressWarnings("unchecked") void redisFailureRejectsNewWorkBeforeDatabaseAdmission() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(new IllegalStateException("offline"));
        assertEquals(503, assertThrows(ApiException.class, () -> service.acquire(USER1, "chat", List.of())).getCode());
        verifyNoInteractions(store);
    }

    @Test void lostLeaseCancelsReactiveUpstreamAndNeverLooksLikeSuccess() {
        var permit = service.acquire(USER1, "chat", List.of());
        var cancelled = new AtomicBoolean(); var failure = new AtomicReference<Throwable>();
        var subscription = permit.guard(Flux.never().doOnCancel(() -> cancelled.set(true)))
                .subscribe(value -> fail("unexpected value"), failure::set, () -> fail("lease loss must be an error"));
        ReflectionTestUtils.invokeMethod(permit, "renew"); // Mock ledger returns false.
        assertTrue(cancelled.get()); assertInstanceOf(ApiException.class, failure.get());
        assertThrows(ApiException.class, permit::requireValid);
        verify(store, never()).release(anyString());
        permit.close(); subscription.dispose();
        verify(store).release(anyString());
    }

    @Test void elapsedLocalDeadlineCannotBeResurrectedByDelayedRenewal() {
        var permit = service.acquire(USER1, "dispatch", List.of());
        ReflectionTestUtils.setField(permit, "validUntil", System.nanoTime() - 1);
        ReflectionTestUtils.invokeMethod(permit, "renew");
        assertThrows(ApiException.class, permit::requireValid);
        verify(store, never()).renew(anyString());
        permit.close();
    }

    @Test void successfulRenewalRetainsValidityAndCloseReleasesOnlyOnce() {
        when(store.renew(anyString())).thenReturn(true);
        var permit = service.acquire(USER1, "dispatch", List.of());
        ReflectionTestUtils.invokeMethod(permit, "renew");
        permit.requireValid(); permit.close(); permit.close();
        assertThrows(ApiException.class, permit::requireValid);
        verify(store).release(anyString());
    }

    @Test void databaseAdmissionFailureCannotReturnAPermit() {
        doThrow(new IllegalStateException("unavailable")).when(store).acquire(any(), anyString(), anyString());
        assertEquals(503, assertThrows(ApiException.class, () -> service.acquire(USER1, "dispatch", List.of())).getCode());
    }

    @Test void reactiveGuardCompletesNormallyAndClosesEvenIfLossPrecedesSubscription() {
        var valid = service.acquire(USER1, "chat", List.of());
        assertEquals("done", valid.guard(Flux.just("done")).doFinally(signal -> valid.close()).blockLast());
        var expired = service.acquire(USER1, "chat", List.of());
        ReflectionTestUtils.invokeMethod(expired, "renew");
        assertThrows(ApiException.class, () -> expired.guard(Flux.just("must not arrive"))
                .doFinally(signal -> expired.close()).blockLast());
        verify(store, times(2)).release(anyString());
    }
}
