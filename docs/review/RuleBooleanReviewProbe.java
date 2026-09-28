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

/** Historical pre-L1 diagnostic; assertions describe the former bug. For fixed-code acceptance,
 * run BooleanRuleDatabaseTest instead. */
public class RuleBooleanReviewProbe {
    public static void main(String[] args) {
        String schema = "review_l_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        var jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("CREATE TABLE records(id INT PRIMARY KEY, tenant_id VARCHAR(16), company_code VARCHAR(16), doc_no VARCHAR(16), dispatched INT, eligible TINYINT) ENGINE=InnoDB");
            jdbc.update("INSERT INTO records VALUES (1,'T001','A','SO1',0,1),(2,'T001','A','SO2',0,2),(3,'T001','A','SO3',0,0)");
            var config = new StandardQueryConfig("records", "id", "company_code", "tenant_id", "doc_no", "number",
                    null, null, null, "dispatched", 0, 1, null,
                    List.of(new StandardQueryConfig.FieldSpec("eligible", "eligible", "boolean", "eligible")), List.of());
            var adapter = new StandardReportAdapter(config, new NamedParameterJdbcTemplate(jdbc));
            adapter.probe();
            var report = spy(new TestCatalog().get(TestCatalog.SALES));
            doReturn(adapter).when(report).adapter();
            var rule = new DispatchRule();
            rule.setId(1L); rule.setVersion(1); rule.setExpression("eligible == true");
            var cache = mock(RuleCache.class);
            when(cache.find("T001", TestCatalog.SALES, "A")).thenReturn(Optional.of(rule));
            var service = new DispatchCandidateService(cache, new RuleEngine());
            assertEquals(Boolean.TRUE, adapter.rowsByIds("T001", List.of("2")).get(0).facts().get("eligible"));
            var trial = service.dryRun("T001", report, "A", rule.getExpression(), Set.of("A"));
            var preview = service.findCandidates("T001", Set.of("A"), List.of(report));
            assertEquals(2, trial.hitCount());
            assertEquals(List.of("SO1"), preview.stream().map(Candidate::docNo).toList());
            System.out.println("L1 reproduced on MySQL: same rule/data, dry-run hits=2, preview hits=1; SO2 (eligible=2) maps to Java true but SQL filters it out.");
        } finally {
            if (schema.matches("review_l_[a-f0-9]{32}")) {
                assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
                jdbc.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }
}
