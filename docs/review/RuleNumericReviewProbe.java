import com.example.report.catalog.query.*;
import com.example.report.rule.*;
import com.example.report.entity.DispatchRule;
import com.example.report.support.TestCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Historical pre-N1 diagnostic: its mismatch assertion must fail after repair.
 * Fixed-code acceptance is NumericRuleDatabaseTest, not this diagnostic.
 * Compare SQL prefiltering with actual JDBC/Aviator evaluation.
 * Only a UUID test schema is used; no app startup or dispatch. */
public class RuleNumericReviewProbe {
    public static void main(String[] args) {
        String schema = "review_n_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        var jdbc = new JdbcTemplate(ds);
        int mismatches = 0;
        try {
            jdbc.execute("CREATE TABLE records(id INT PRIMARY KEY,tenant_id VARCHAR(16),company_code VARCHAR(16),doc_no VARCHAR(16),dispatched INT,amount DECIMAL(36,16)) ENGINE=InnoDB");
            jdbc.update("INSERT INTO records VALUES (1,'T001','A','SO1',0,1),(2,'T001','A','SO2',0,1.9),"
                    + "(3,'T001','A','SO3',0,1.0000000000000001),(4,'T001','A','SO4',0,9007199254740992),"
                    + "(5,'T001','A','SO5',0,9007199254740993)");
            for (String type : List.of("decimal", "long", "integer")) {
                // The large values cannot fit integer; keep this case inside its normal range.
                if (type.equals("integer")) jdbc.update("DELETE FROM records WHERE id>=4");
                var config = new StandardQueryConfig("records", "id", "company_code", "tenant_id", "doc_no", "number",
                        null, "amount", null, "dispatched", 0, 1, null,
                        List.of(new StandardQueryConfig.FieldSpec("amount", "amount", type, "amount")), List.of());
                var adapter = new StandardReportAdapter(config, new NamedParameterJdbcTemplate(jdbc));
                adapter.probe();
                var report = spy(new TestCatalog().get(TestCatalog.SALES));
                doReturn(adapter).when(report).adapter();
                var rule = new DispatchRule(); rule.setId(1L); rule.setVersion(1);
                var cache = mock(RuleCache.class);
                when(cache.find("T001", TestCatalog.SALES, "A")).thenReturn(Optional.of(rule));
                var service = new DispatchCandidateService(cache, new RuleEngine());
                for (String expression : List.of("amount == 1", "amount < 1.5", "amount == 1.0000000000000001",
                        "amount < 1.0000000000000001", "amount == 9007199254740993.0", "amount >= 9007199254740993.0")) {
                    rule.setExpression(expression);
                    var trial = service.dryRun("T001", report, "A", expression, Set.of("A"));
                    var preview = service.findCandidates("T001", Set.of("A"), List.of(report));
                    var trialIds = trial.samples().stream().map(Candidate::docNo).toList();
                    var previewIds = preview.stream().map(Candidate::docNo).toList();
                    if (!trialIds.equals(previewIds)) {
                        mismatches++;
                        System.out.println("MISMATCH type=" + type + " rule=" + expression + " trial=" + trialIds + " preview=" + previewIds);
                    }
                }
            }
            assertTrue(mismatches > 0, "Diagnostic expects a defect; replace with equality tests after repair.");
            System.out.println("Numeric diagnostic mismatches=" + mismatches + "; successful exit means defect reproduced.");
        } finally {
            if (schema.matches("review_n_[a-f0-9]{32}")) {
                assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
                jdbc.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }
}
