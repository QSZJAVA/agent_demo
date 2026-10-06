package com.example.report.config;

import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated MySQL schema and synthetic Redis tenant keys, never a shared FLUSHDB/restart. */
@EnabledIfEnvironmentVariable(named = "TRACE_IT", matches = "true")
class QuotaLeaseDatabaseTest {
    static String schema;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    QuotaLeaseStore store;
    ResourceQuotaService first;
    ResourceQuotaService second;
    CurrentUser user;
    final List<ResourceQuotaService.Permit> held = new ArrayList<>();

    @BeforeAll static void database() {
        schema = "quota_it_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        Flyway.configure().dataSource(ds).load().migrate();
        var config = new RedisStandaloneConfiguration(System.getenv().getOrDefault("TRACE_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("TRACE_REDIS_PORT", "6379")));
        String password = System.getenv("TRACE_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(password);
        factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll static void cleanupDatabase() {
        if (factory != null) factory.destroy();
        if (jdbc != null && schema != null && schema.matches("quota_it_[a-f0-9]{32}")) {
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }

    @BeforeEach void setup() {
        store = new QuotaLeaseStore(jdbc, manager);
        first = new ResourceQuotaService(redis, store);
        second = new ResourceQuotaService(redis, new QuotaLeaseStore(jdbc, manager));
        user = new CurrentUser("quota_test_" + UUID.randomUUID().toString().replace("-", ""), "probe", "probe", Set.of(), Set.of(), false);
    }

    List<String> keys() {
        String base = "quota:dispatch:" + user.tenantId() + ":";
        return List.of(base + "minute:user:probe", base + "minute:tenant", base + "active:user:probe", base + "active:tenant");
    }

    @AfterEach void cleanup() {
        held.forEach(ResourceQuotaService.Permit::close);
        first.shutdown(); second.shutdown();
        redis.delete(keys());
        // This connection belongs to our isolated UUID schema.
        jdbc.update("DELETE FROM resource_quota_lease");
    }

    ResourceQuotaService.Permit acquire(ResourceQuotaService service) {
        var permit = service.acquire(user, "dispatch", List.of()); held.add(permit); return permit;
    }

    @Test void redisStateLossCannotGrantFourMorePermitsOnAnotherInstance() {
        for (int i = 0; i < 4; i++) acquire(first);
        assertEquals(429, assertThrows(ApiException.class, () -> acquire(second)).getCode());
        redis.delete(keys());
        held.forEach(p -> ReflectionTestUtils.invokeMethod(p, "renew"));
        held.forEach(ResourceQuotaService.Permit::requireValid);
        assertEquals(429, assertThrows(ApiException.class, () -> acquire(second)).getCode());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM resource_quota_lease", Integer.class));
    }

    @Test void concurrentInstancesAdmitOnlyFourForOneUser() throws Exception {
        var pool = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        List<Future<ResourceQuotaService.Permit>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                var service = i % 2 == 0 ? first : second;
                pending.add(pool.submit(() -> {
                    start.await();
                    try { return service.acquire(user, "dispatch", List.of()); }
                    catch (ApiException e) { assertEquals(429, e.getCode()); return null; }
                }));
            }
            start.countDown();
            for (var future : pending) { var p = future.get(15, TimeUnit.SECONDS); if (p != null) held.add(p); }
            assertEquals(4, held.size());
        } finally { pool.shutdownNow(); }
    }

    @Test void expiredLeaseCannotBeRenewedAndOldCloseCannotReleaseNewToken() {
        var old = acquire(first);
        String token = (String) ReflectionTestUtils.getField(old, "token");
        jdbc.update("UPDATE resource_quota_lease SET expires_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE token=?", token);
        ReflectionTestUtils.invokeMethod(old, "renew");
        assertThrows(ApiException.class, old::requireValid);
        assertThrows(ApiException.class, () -> old.lossSignal().block(Duration.ofSeconds(1)));
        var fresh = acquire(second);
        old.close(); old.close();
        fresh.requireValid();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM resource_quota_lease", Integer.class));
        assertFalse(store.renew(token));
    }

    @Test void failedRenewalInvalidatesWorkButKeepsOccupancyUntilClose() {
        var failing = spy(store);
        first.shutdown(); first = new ResourceQuotaService(redis, failing);
        for (int i = 0; i < 4; i++) acquire(first);
        doThrow(new IllegalStateException("database unavailable")).when(failing).renew(anyString());
        ReflectionTestUtils.invokeMethod(held.get(0), "renew");
        assertThrows(ApiException.class, held.get(0)::requireValid);
        assertEquals(429, assertThrows(ApiException.class, () -> acquire(second)).getCode());
        held.get(0).close();
        acquire(second).requireValid();
    }

    @Test void releaseCommitsEvenWhenSurroundingBusinessTransactionRollsBack() {
        var permit = acquire(first);
        new TransactionTemplate(manager).executeWithoutResult(status -> { permit.close(); status.setRollbackOnly(); });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource_quota_lease", Integer.class));
    }

    @Test void tenantLimitAppliesAcrossUsersAndDoesNotCrossTenantOrOperation() {
        for (int i = 0; i < 20; i++) {
            var distinct = new CurrentUser(user.tenantId(), "user" + i, "probe", Set.of(), Set.of(), false);
            store.acquire(distinct, "dispatch", UUID.randomUUID().toString());
        }
        assertEquals(429, assertThrows(ApiException.class, () -> store.acquire(user, "dispatch", UUID.randomUUID().toString())).getCode());
        store.acquire(user, "preview", UUID.randomUUID().toString());
        store.acquire(new CurrentUser("another_tenant", user.userId(), "probe", Set.of(), Set.of(), false), "dispatch", UUID.randomUUID().toString());
    }
}
