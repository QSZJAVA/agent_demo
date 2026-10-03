package com.example.report.config;

import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Redis负责调用速率限制，MySQL独立事务租约负责真实并发配额；业务处理中持续续租，失去租约后必须停止旧任务。 */
@Slf4j
@Component
public class ResourceQuotaService {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);
    private final StringRedisTemplate redis;
    private final QuotaLeaseStore leases;
    private final Set<Permit> permits = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "resource-quota-renewal");
        thread.setDaemon(true);
        return thread;
    });

    public ResourceQuotaService(StringRedisTemplate redis, QuotaLeaseStore leases) {
        this.redis = redis;
        this.leases = leases;
    }

    public Permit acquire(CurrentUser user, String operation, Collection<String> reportIds) {
        String base = "quota:" + operation + ":" + user.tenantId() + ":";
        checkRate(base + "minute:user:" + user.userId(), "chat".equals(operation) ? 30 : 12);
        checkRate(base + "minute:tenant", "chat".equals(operation) ? 300 : 120);
        if (reportIds != null) {
            for (String reportId : reportIds) checkRate(base + "minute:report:" + reportId, 30);
        }
        String token = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Permit permit = null;
        try {
            leases.acquire(user, operation, token);
            permit = new Permit(token, started);
            permits.add(permit);
            permit.renewal = renewals.scheduleAtFixedRate(permit::renew, 30, 30, TimeUnit.SECONDS);
            permit.requireValid();
            return permit;
        } catch (RuntimeException e) {
            if (permit != null) permit.close();
            else release(token);
            if (e instanceof ApiException api) throw api;
            log.warn("并发配额获取失败 operation={} token={}", operation, token, e);
            throw unavailable();
        }
    }

    private void checkRate(String key, int limit) {
        try {
            Long count = redis.execute(INCREMENT, List.of(key), "60000");
            if (count == null) throw unavailable();
            if (count > limit) throw new ApiException(429, "请求过于频繁，请稍后重试");
        } catch (RuntimeException e) {
            if (e instanceof ApiException api) throw api;
            throw unavailable();
        }
    }

    private void release(String token) {
        try { leases.release(token); }
        catch (RuntimeException e) { log.warn("并发配额释放失败，将等待租约到期 token={}", token, e); }
    }

    private static ApiException unavailable() {
        return new ApiException(503, "资源保护服务暂不可用，请稍后重试");
    }

    /** Null is only used by service harnesses without the optional quota dependency.*/
    public static void check(Permit permit) {
        if(Thread.currentThread().isInterrupted()) throw new ApiException(409,"任务已中断，请刷新并核对结果");
        if (permit != null) permit.requireValid();
    }

    public final class Permit implements AutoCloseable {
        private final String token;
        private final Sinks.One<Void> loss = Sinks.one();
        private volatile long validUntil;
        private volatile boolean closed;
        private volatile boolean invalid;
        private volatile ScheduledFuture<?> renewal;

        private Permit(String token, long started) {
            this.token = token;
            // Stop locally before DB expiry; network/lock waits count against validity.
            validUntil = started + TimeUnit.SECONDS.toNanos(QuotaLeaseStore.LEASE_SECONDS - 10);
        }

        public void requireValid() {
            if (!closed && !invalid && System.nanoTime() - validUntil >= 0) invalidate();
            if (closed || invalid) throw new ApiException(503, "运行配额已失效，任务已停止，请刷新结果后重试");
        }

        /** A lost lease cancels reactive model work; it is never signalled as normal completion. */
        public Mono<Void> lossSignal() { return loss.asMono(); }

        public <T> Flux<T> guard(Flux<T> work) {
            return Flux.defer(() -> {
                requireValid();
                // Complete the stop publisher to force upstream cancellation, then report
                // lease loss as an error. takeUntilOther alone need not cancel on other.onError.
                return work.takeUntilOther(lossSignal().onErrorComplete())
                        .concatWith(Mono.defer(() -> { requireValid(); return Mono.empty(); }));
            });
        }

        private synchronized void renew() {
            if (closed || invalid) return;
            long started = System.nanoTime();
            if (started - validUntil >= 0) { invalidate(); return; }
            try {
                if (!leases.renew(token) || System.nanoTime() - validUntil >= 0) {
                    invalidate();
                    return;
                }
                validUntil = started + TimeUnit.SECONDS.toNanos(QuotaLeaseStore.LEASE_SECONDS - 10);
            } catch (RuntimeException e) {
                log.warn("并发配额续租失败，停止后续工作 token={}", token, e);
                invalidate();
            }
        }

        private void invalidate() {
            invalid = true;
            ScheduledFuture<?> task = renewal;
            if (task != null) task.cancel(false);
            loss.tryEmitError(new ApiException(503, "运行配额已失效，任务已停止，请刷新结果后重试"));
            // Keep occupancy until the caller unwinds/closes or the DB lease expires.
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            ScheduledFuture<?> task = renewal;
            if (task != null) task.cancel(false);
            permits.remove(this);
            release(token);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        permits.forEach(Permit::invalidate);
        renewals.shutdownNow();
    }
}
