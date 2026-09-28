import com.example.report.catalog.query.*;
import com.example.report.dispatch.*;
import com.example.report.rule.*;
import com.example.report.support.TestCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Historical pre-O2 diagnostic. Fixed-code acceptance is SourceIdentityDatabaseTest.
 * Only synthetic rows in a random schema; exercise the real local demo gateway transaction. */
public class CompositeIdReviewProbe {
    public static void main(String[] args) {
        String schema = "review_o_key_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        try {
            jdbc.execute("CREATE TABLE records(tenant_id VARCHAR(16),company_code VARCHAR(16),id INT,doc_no VARCHAR(16),dispatched INT,PRIMARY KEY(tenant_id,company_code,id)) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE dispatch_gateway_request(tenant_id VARCHAR(64),request_id VARCHAR(128),report_id VARCHAR(128),record_id VARCHAR(128),status VARCHAR(32),error_code VARCHAR(64),message VARCHAR(512),created_at DATETIME,PRIMARY KEY(tenant_id,request_id)) ENGINE=InnoDB");
            jdbc.update("INSERT INTO records VALUES ('T001','A',7,'SO-A',0),('T001','B',7,'SO-B',0)");
            var config = new StandardQueryConfig("records", "id", "company_code", "tenant_id", "doc_no", "number",
                    null,null,null,"dispatched",0,1,null,List.of(),List.of());
            var adapter = new StandardReportAdapter(config, new NamedParameterJdbcTemplate(jdbc));
            adapter.probe(); // This source configuration is currently accepted.
            var report = spy(new TestCatalog().get(TestCatalog.SALES));
            doReturn(adapter).when(report).adapter();
            var gateway = new MockDispatchGateway(jdbc, mock(RuleCache.class), new RuleEngine());
            var request = new DispatchGateway.DispatchRequest("T001","probe-request",report,
                    candidate(TestCatalog.SALES,"7","SO-A","A","selected A only"),false);
            var outcome = tx.execute(status -> gateway.dispatch(request));
            assertNotNull(outcome); assertFalse(outcome.success());
            assertEquals("RECORD_CHANGED", outcome.errorCode());
            assertEquals(1, jdbc.queryForObject("SELECT dispatched FROM records WHERE company_code='A'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT dispatched FROM records WHERE company_code='B'", Integer.class));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM dispatch_gateway_request", String.class));
            System.out.println("O2 reproduced: accepted composite-key source; dispatch for company A / id 7 marked BOTH A and B dispatched, while gateway request was committed as FAILED.");
        } finally {
            if (!schema.matches("review_o_key_[a-f0-9]{32}")) throw new IllegalStateException("unexpected schema");
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }
}
