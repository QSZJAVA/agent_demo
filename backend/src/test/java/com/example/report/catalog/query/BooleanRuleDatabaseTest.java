package com.example.report.catalog.query;

import com.example.report.entity.DispatchRule;
import com.example.report.rule.*;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在独立MySQL库核对JDBC布尔转换与预览谓词；目录字段必须与测试适配器保持一致。 */
@EnabledIfEnvironmentVariable(named = "TRACE_IT", matches = "true")
class BooleanRuleDatabaseTest {
    static String schema;
    static JdbcTemplate jdbc;
    static StandardReportAdapter adapter;

    @BeforeAll static void database() {
        schema = "boolean_it_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE records(id INT PRIMARY KEY,tenant_id VARCHAR(16),company_code VARCHAR(16),doc_no VARCHAR(16),dispatched INT,eligible TINYINT) ENGINE=InnoDB");
        jdbc.update("INSERT INTO records VALUES (1,'T001','A','SO1',0,1),(2,'T001','A','SO2',0,2),"
                + "(3,'T001','A','SO3',0,0),(4,'T001','A','SO4',0,-1),(5,'T001','A','SO5',0,-2),(6,'T001','A','SO6',0,NULL)");
        var config = new StandardQueryConfig("records", "id", "company_code", "tenant_id", "doc_no", "number",
                null, null, null, "dispatched", 0, 1, null,
                List.of(new StandardQueryConfig.FieldSpec("eligible", "eligible", "boolean", "eligible")), List.of());
        adapter = new StandardReportAdapter(config, new NamedParameterJdbcTemplate(jdbc));
        adapter.probe();
    }

    @AfterAll static void cleanup() {
        if (jdbc != null && schema != null && schema.matches("boolean_it_[a-f0-9]{32}")) {
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"eligible == true", "eligible != true", "eligible < true", "eligible <= true",
            "eligible > true", "eligible >= true", "eligible == false", "eligible != false", "eligible < false",
            "eligible <= false", "eligible > false", "eligible >= false", "eligible == nil", "eligible != nil",
            "eligible == true && eligible != nil"})
    void previewAndTrialHaveIdenticalRecordSets(String expression) {
        var report = spy(new TestCatalog().get(TestCatalog.SALES));
        doReturn(adapter).when(report).adapter();
        doReturn(adapter.fields()).when(report).fields();
        var rule = new DispatchRule(); rule.setId(1L); rule.setVersion(1); rule.setExpression(expression);
        var cache = mock(RuleCache.class);
        when(cache.find("T001", TestCatalog.SALES, "A")).thenReturn(Optional.of(rule));
        var service = new DispatchCandidateService(cache, new RuleEngine());
        var trial = service.dryRun("T001", report, "A", expression, Set.of("A"));
        var preview = service.findCandidates("T001", Set.of("A"), List.of(report));
        assertEquals(6, trial.total());
        assertEquals(trial.hitCount(), preview.size(), expression);
        assertEquals(trial.samples().stream().map(Candidate::recordId).toList(),
                preview.stream().map(Candidate::recordId).toList(), expression);
        if ("eligible == true".equals(expression)) {
            assertEquals(Boolean.TRUE, adapter.rowsByIds("T001", List.of("2")).get(0).facts().get("eligible"));
            assertTrue(preview.stream().anyMatch(c -> "2".equals(c.recordId())));
        }
    }
}
