import com.example.report.catalog.query.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read/write only a UUID schema, testing string-bound cursors for numeric source IDs. */
public class LargeIdReviewProbe {
    public static void main(String[] args) {
        String schema = "review_o_ids_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        var jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("CREATE TABLE records (id BIGINT UNSIGNED PRIMARY KEY,tenant_id VARCHAR(16),company_code VARCHAR(16),doc_no VARCHAR(40),dispatched INT) ENGINE=InnoDB");
            var config = new StandardQueryConfig("records", "id", "company_code", "tenant_id", "doc_no", "number",
                    null, null, null, "dispatched", 0, 1, null, List.of(), List.of());
            var adapter = new StandardReportAdapter(config, new NamedParameterJdbcTemplate(jdbc));
            for (String start : List.of("9007199254740991", "9223372036854774800", "18446744073709550000")) {
                jdbc.update("DELETE FROM records");
                BigInteger base = new BigInteger(start);
                List<Object[]> rows = new ArrayList<>();
                for (int i = 0; i < 1002; i++) {
                    String id = base.add(BigInteger.valueOf(i)).toString();
                    rows.add(new Object[]{id, "T001", "A", id, 0});
                }
                jdbc.batchUpdate("INSERT INTO records VALUES (?,?,?,?,?)", rows);
                List<String> actual = new ArrayList<>();
                String after = null;
                for (int page = 0; page < 5; page++) {
                    var found = adapter.pendingRowsAfterWithRule("T001", Set.of("A"), after, 500, null);
                    actual.addAll(found.stream().map(FactRow::recordId).toList());
                    if (found.size() < 500) break;
                    after = found.get(found.size() - 1).recordId();
                }
                System.out.println("numeric cursor start=" + start + " expected=1002 actual=" + actual.size() + " distinct=" + new HashSet<>(actual).size());
                var one = adapter.rowsByIds("T001", List.of(base.add(BigInteger.valueOf(500)).toString()));
                System.out.println("single-ID lookup rows=" + one.size());
                assertEquals(1002, actual.size());
                assertEquals(1002, new HashSet<>(actual).size());
                assertEquals(1, one.size());
            }
        } finally {
            if (!schema.matches("review_o_ids_[a-f0-9]{32}")) throw new IllegalStateException("unexpected schema");
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }
}
