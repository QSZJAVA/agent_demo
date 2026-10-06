package com.example.report;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DEMO_IT", matches = "true")
class TenantMigrationIntegrationTest {
    @Test void upgradesExistingSingleTenantTablesWithoutResettingData() throws Exception {
        String schema = "review_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String source = System.getenv().getOrDefault("SPRING_DATASOURCE_URL",
                "jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "localhost") + ":"
                        + System.getenv().getOrDefault("DB_PORT", "3306") + "/report_demo?createDatabaseIfNotExist=true");
        String url = source.replaceFirst("/[^/?]+(?=\\?|$)", "/" + schema);
        String user = System.getenv().getOrDefault("DB_USERNAME", "root");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        Flyway.configure().dataSource(url, user, password).target("5").load().migrate();
        try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
            try {
                sql.execute("CREATE TABLE report_sales (id BIGINT PRIMARY KEY, company_code VARCHAR(10), dispatch_status INT)");
                sql.execute("INSERT INTO report_sales VALUES (42, 'A', 1)");
                sql.execute("INSERT INTO dispatch_rule (report_id,company_code,name,expression,version,status,created_at) "
                        + "VALUES ('rpt-sales-order','A','preserved','amount > 20',8,'published',NOW())");
                Flyway.configure().dataSource(url, user, password).load().migrate();
                try (var row = sql.executeQuery("SELECT tenant_id, dispatch_status FROM report_sales WHERE id=42")) {
                    assertTrue(row.next());
                    assertEquals("T001", row.getString(1));
                    assertEquals(1, row.getInt(2));
                }
                try (var row = sql.executeQuery("SELECT tenant_id, version FROM dispatch_rule WHERE name='preserved'")) {
                    assertTrue(row.next());
                    assertEquals("T001", row.getString(1));
                    assertEquals(8, row.getInt(2));
                }
                try (var row = sql.executeQuery("SELECT query_config->>'$.tenantColumn' FROM report_definition WHERE report_id='rpt-sales-order'")) {
                    assertTrue(row.next());
                    assertEquals("tenant_id", row.getString(1));
                }
            } finally {
                // Only the UUID-named schema created by this test is removed.
                sql.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }
}
