package com.example.report.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real Flyway callback ordering and data preservation, confined to this test's UUID schema. */
@EnabledIfEnvironmentVariable(named = "TRACE_IT", matches = "true")
class DemoBootstrapDatabaseTest {
    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createIsolatedDatabase() {
        schema = "bootstrap_it_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource("jdbc:mysql://"
                + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1") + ":"
                + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"),
                System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
    }

    @AfterEach
    void removeOnlyThisTestsDatabase() {
        if (jdbc == null || schema == null) return;
        assertTrue(schema.matches("bootstrap_it_[a-f0-9]{32}"));
        assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
        jdbc.execute("DROP DATABASE `" + schema + "`");
    }

    @Test
    void initializesFreshDatabaseAndPreservesEditsAcrossRestart() {
        migrate();
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM report_sales", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM report_definition", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_rule", Integer.class));
        jdbc.update("UPDATE report_sales SET amount=555,dispatch_status=1 WHERE id=1");
        jdbc.update("UPDATE report_definition SET report_name='preserved name',catalog_version=42 WHERE report_id='rpt-sales-order'");
        jdbc.update("UPDATE dispatch_rule SET expression='amount > 12345',version=42 WHERE report_id='rpt-sales-order' AND company_code='*'");

        migrate();

        assertEquals(new BigDecimal("555.00"), jdbc.queryForObject("SELECT amount FROM report_sales WHERE id=1", BigDecimal.class));
        assertEquals(1, jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=1", Integer.class));
        assertEquals("preserved name", jdbc.queryForObject("SELECT report_name FROM report_definition WHERE report_id='rpt-sales-order'", String.class));
        assertEquals(42, jdbc.queryForObject("SELECT version FROM dispatch_rule WHERE report_id='rpt-sales-order' AND company_code='*'", Integer.class));
    }

    @Test
    void emptyDirectoryInAnExistingDatabaseDoesNotCauseReset() {
        migrate();
        jdbc.update("DELETE FROM report_alias");
        jdbc.update("DELETE FROM report_definition");
        jdbc.update("UPDATE report_sales SET dispatch_status=1 WHERE id=1");
        jdbc.update("DELETE FROM dispatch_rule_history");
        jdbc.update("DELETE FROM dispatch_rule");

        migrate();

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM report_definition", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_rule", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=1", Integer.class));
    }

    @Test
    void baselineOfAnExistingSchemaCannotTriggerDemoInitialization() {
        jdbc.execute("CREATE TABLE preserved_configuration (id INT PRIMARY KEY,value_text VARCHAR(64))");
        jdbc.update("INSERT INTO preserved_configuration VALUES (1,'existing business configuration')");

        assertThrows(org.flywaydb.core.api.FlywayException.class, this::migrate);

        assertEquals("existing business configuration", jdbc.queryForObject(
                "SELECT value_text FROM preserved_configuration WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='report_sales'", Integer.class));
    }

    private void migrate() {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }
}
