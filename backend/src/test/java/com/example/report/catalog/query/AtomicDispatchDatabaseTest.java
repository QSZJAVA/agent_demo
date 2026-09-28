package com.example.report.catalog.query;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated UUID schema only; no application startup, demo reset or external gateway. */
@EnabledIfEnvironmentVariable(named = "TRACE_IT", matches = "true")
class AtomicDispatchDatabaseTest {
    static String schema;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static StandardReportAdapter adapter;

    @BeforeAll static void database() {
        schema = "guard_it_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE records (id BIGINT PRIMARY KEY,tenant_id VARCHAR(16),company_code VARCHAR(16),doc_no VARCHAR(16),amount DECIMAL(18,2),dispatched INT) ENGINE=InnoDB");
        var config = new StandardQueryConfig("records", "id", "company_code", "tenant_id", "doc_no", "number",
                null, "amount", null, "dispatched", 0, 1, null,
                List.of(new StandardQueryConfig.FieldSpec("amount", "amount", "decimal", "amount")), List.of());
        adapter = new StandardReportAdapter(config, new NamedParameterJdbcTemplate(jdbc));
    }

    @AfterAll static void cleanup() {
        if (jdbc != null && schema != null && schema.matches("guard_it_[a-f0-9]{32}")) {
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM records");
        jdbc.update("INSERT INTO records VALUES (1,'T001','A','SO1',2000,0)");
    }

    @Test void committedCompanyOrRuleFactChangesAreRejectedWithoutWriting() {
        jdbc.update("UPDATE records SET company_code='B' WHERE id=1");
        assertEquals(Boolean.FALSE, tx.execute(s -> adapter.markDispatchedGuarded("T001", "1", "A", LocalDateTime.now(), r -> true)));
        jdbc.update("UPDATE records SET company_code='A',amount=1 WHERE id=1");
        assertEquals(Boolean.FALSE, tx.execute(s -> adapter.markDispatchedGuarded("T001", "1", "A", LocalDateTime.now(), r -> r.amount().intValue() > 20)));
        assertEquals(0, jdbc.queryForObject("SELECT dispatched FROM records WHERE id=1", Integer.class));
    }

    @Test void competingWriterCannotChangeFactsBetweenGuardAndDispatchCommit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var dispatch = pool.submit(() -> tx.execute(s -> adapter.markDispatchedGuarded("T001", "1", "A", LocalDateTime.now(), r -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return r.amount().intValue() > 20;
            })));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var writer = pool.submit(() -> {
                writerStarted.countDown();
                return jdbc.update("UPDATE records SET company_code='B',amount=1 WHERE id=1 AND dispatched=0");
            });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(dispatch.get(5, TimeUnit.SECONDS));
            assertEquals(0, writer.get(5, TimeUnit.SECONDS));
            assertEquals("A", jdbc.queryForObject("SELECT company_code FROM records WHERE id=1", String.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
