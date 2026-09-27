package com.example.report.config;

import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Redis 原子计数器：按用户、租户和报表限制请求频率与同时运行的昂贵任务。 */
@Component
public class ResourceQuotaService {

    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);
    private static final long LEASE_MS = 300_000;
    private static final DefaultRedisScript<Long> ACQUIRE_LEASE = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',ARGV[1]-ARGV[2]); "
                    + "if redis.call('ZCARD',KEYS[1]) >= tonumber(ARGV[3]) then return 0 end; "
                    + "redis.call('ZADD',KEYS[1],ARGV[1],ARGV[4]); redis.call('PEXPIRE',KEYS[1],ARGV[2]); return 1",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_LEASE = new DefaultRedisScript<>(
            "if redis.call('ZSCORE',KEYS[1],ARGV[2]) then "
                    + "redis.call('ZADD',KEYS[1],ARGV[1],ARGV[2]); redis.call('PEXPIRE',KEYS[1],ARGV[3]); return 1 end; return 0",
            Long.class);
    private static final DefaultRedisScript<Long> RELEASE_LEASE = new DefaultRedisScript<>(
            "return redis.call('ZREM',KEYS[1],ARGV[1])", Long.class);
    private final StringRedisTemplate redis;
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "resource-quota-renewal");
        thread.setDaemon(true);
        return thread;
    });

    public ResourceQuotaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Permit acquire(CurrentUser user, String operation, Collection<String> reportIds) {
        String base = "quota:" + operation + ":" + user.tenantId() + ":";
        int userRate = "chat".equals(operation) ? 30 : 12;
        int tenantRate = "chat".equals(operation) ? 300 : 120;
        check(base + "minute:user:" + user.userId(), userRate, 60_000);
        check(base + "minute:tenant", tenantRate, 60_000);
        if (reportIds != null) {
            for (String reportId : reportIds) {
                check(base + "minute:report:" + reportId, 30, 60_000);
            }
        }
        List<String> held = new ArrayList<>();
        String token = UUID.randomUUID().toString();
        try {
            hold(base + "active:user:" + user.userId(), 4, token, held);
            hold(base + "active:tenant", 20, token, held);
            return new Permit(redis, held, token, renewals);
        } catch (RuntimeException e) {
            held.forEach(key -> release(key, token));
            throw e;
        }
    }

    private void check(String key, int limit, int ttlMs) {
        Long count = increment(key, ttlMs);
        if (count > limit) throw new ApiException(429, "请求过于频繁，请稍后重试");
    }

    private void hold(String key, int limit, String token, List<String> held) {
        Long acquired;
        try {
            acquired = redis.execute(ACQUIRE_LEASE, List.of(key),
                    String.valueOf(System.currentTimeMillis()), String.valueOf(LEASE_MS), String.valueOf(limit), token);
        } catch (RuntimeException e) {
            throw new ApiException(503, "资源保护服务暂不可用，请稍后重试");
        }
        if (acquired == null) throw new ApiException(503, "资源保护服务暂不可用，请稍后重试");
        if (acquired == 0) {
            throw new ApiException(429, "同时运行的任务过多，请稍后重试");
        }
        held.add(key);
    }

    private void release(String key, String token) {
        try { redis.execute(RELEASE_LEASE, List.of(key), token); } catch (RuntimeException ignored) { }
    }

    private Long increment(String key, int ttlMs) {
        try {
            Long count = redis.execute(INCREMENT, List.of(key), String.valueOf(ttlMs));
            if (count == null) throw new IllegalStateException("配额计数不可用");
            return count;
        } catch (RuntimeException e) {
            if (e instanceof ApiException) throw e;
            throw new ApiException(503, "资源保护服务暂不可用，请稍后重试");
        }
    }

    public static final class Permit implements AutoCloseable {
        private final StringRedisTemplate redis;
        private final List<String> keys;
        private final String token;
        private final ScheduledFuture<?> renewal;
        private boolean closed;

        private Permit(StringRedisTemplate redis, List<String> keys, String token, ScheduledExecutorService scheduler) {
            this.redis = redis;
            this.keys = keys;
            this.token = token;
            this.renewal = scheduler.scheduleAtFixedRate(this::renew, 60, 60, TimeUnit.SECONDS);
        }

        private synchronized void renew() {
            if (closed) return;
            for (String key : keys) {
                try {
                    redis.execute(RENEW_LEASE, List.of(key), String.valueOf(System.currentTimeMillis()),
                            token, String.valueOf(LEASE_MS));
                } catch (RuntimeException ignored) { /* 下次刷新继续尝试；租约到期后由 Redis 清理。 */ }
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            renewal.cancel(false);
            keys.forEach(key -> {
                try { redis.execute(RELEASE_LEASE, List.of(key), token); } catch (RuntimeException ignored) { }
            });
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        renewals.shutdownNow();
    }
}
