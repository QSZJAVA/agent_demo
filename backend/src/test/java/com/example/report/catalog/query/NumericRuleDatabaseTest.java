package com.example.report.catalog.query;

import com.example.report.entity.DispatchRule;
import com.example.report.rule.*;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在独立MySQL库核对SQL预筛选与规则试算；覆盖有损类型映射和浮点字面量舍入。 */
@EnabledIfEnvironmentVariable(named = "TRACE_IT", matches = "true")
class NumericRuleDatabaseTest {
    static String schema;
    static JdbcTemplate jdbc;

    @BeforeAll static void database() {
        schema = "numeric_it_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds);
        for (String type : List.of("decimal", "long", "integer")) {
            String table = "records_" + type;
            jdbc.execute("CREATE TABLE " + table + "(id INT PRIMARY KEY,tenant_id VARCHAR(16),company_code VARCHAR(16),doc_no VARCHAR(16),dispatched INT,amount DECIMAL(36,16)) ENGINE=InnoDB");
            jdbc.update("INSERT INTO " + table + " VALUES (1,'T001','A','SO1',0,1),(2,'T001','A','SO2',0,1.9),"
                    + "(3,'T001','A','SO3',0,1.0000000000000001),(4,'T001','A','SO4',0,-1),"
                    + "(5,'T001','A','SO5',0,-1.9),(6,'T001','A','SO6',0,NULL),(7,'T001','A','SO7',0,0)");
            if (!type.equals("integer")) jdbc.update("INSERT INTO " + table
                    + " VALUES (8,'T001','A','SO8',0,9007199254740992),(9,'T001','A','SO9',0,9007199254740993)");
        }
    }

    @AfterAll static void cleanup() {
        if (jdbc != null && schema != null && schema.matches("numeric_it_[a-f0-9]{32}")) {
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of("decimal", "long", "integer").flatMap(type -> Stream.of(
                "amount == 1", "amount < 1.5", "amount <= 1", "amount > -1", "amount >= -1", "amount != 1",
                "amount == 1.0000000000000001", "amount < 1.0000000000000001", "amount >= 1.0000000000000001",
                "amount == 9007199254740993.0", "amount >= 9007199254740993.0", "amount == 9007199254740993",
                "amount == nil", "amount != nil", "amount > 0 && amount < 2", "amount >= -1 && amount < 1.5",
                "amount > 9223372036854775808", "amount < -9223372036854775809")
                .map(expression -> Arguments.of(type, expression)));
    }

    @ParameterizedTest @MethodSource("cases")
    void trialAndPreviewHaveTheSameRecordSet(String type, String expression) {
        var config = new StandardQueryConfig("records_" + type, "id", "company_code", "tenant_id", "doc_no", "number",
                null, "amount", null, "dispatched", 0, 1, null,
                List.of(new StandardQueryConfig.FieldSpec("amount", "amount", type, "amount")), List.of());
        var adapter = new StandardReportAdapter(config, new NamedParameterJdbcTemplate(jdbc));
        adapter.probe();
        var report = spy(new TestCatalog().get(TestCatalog.SALES));
        doReturn(adapter).when(report).adapter();
        doReturn(adapter.fields()).when(report).fields();
        var rule = new DispatchRule(); rule.setId(1L); rule.setVersion(1); rule.setExpression(expression);
        var cache = mock(RuleCache.class);
        when(cache.find("T001", TestCatalog.SALES, "A")).thenReturn(Optional.of(rule));
        var service = new DispatchCandidateService(cache, new RuleEngine());
        var trial = service.dryRun("T001", report, "A", expression, Set.of("A"));
        var preview = service.findCandidates("T001", Set.of("A"), List.of(report));
        assertEquals(0, trial.errorCount());
        assertEquals(trial.samples().stream().map(Candidate::recordId).toList(),
                preview.stream().map(Candidate::recordId).toList(), type + " / " + expression);
        // Verify ordinary safe rules still filter in SQL, rather than solving the bug by disabling all pushdown.
        if (type.equals("decimal") && expression.equals("amount == 1")) {
            assertEquals(List.of("1"), adapter.pendingRowsAfterWithRule("T001", Set.of("A"), null, 500, expression)
                    .stream().map(FactRow::recordId).toList());
        }
    }
}
