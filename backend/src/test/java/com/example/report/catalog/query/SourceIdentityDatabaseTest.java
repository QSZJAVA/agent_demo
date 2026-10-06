package com.example.report.catalog.query;

import com.example.report.common.ApiException;
import com.example.report.dispatch.*;
import com.example.report.rule.*;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "TRACE_IT", matches = "true")
class SourceIdentityDatabaseTest {
    static String schema;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

    @BeforeAll static void setupDatabase() {
        schema = "identity_it_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE dispatch_gateway_request(tenant_id VARCHAR(64),request_id VARCHAR(128),report_id VARCHAR(128),record_id VARCHAR(128),status VARCHAR(32),error_code VARCHAR(64),message VARCHAR(512),created_at DATETIME,PRIMARY KEY(tenant_id,request_id)) ENGINE=InnoDB");
    }
    @AfterAll static void cleanup() {
        if (jdbc != null && schema != null && schema.matches("identity_it_[a-f0-9]{32}")) {
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }
    @BeforeEach void reset() { jdbc.execute("DROP TABLE IF EXISTS records"); jdbc.update("DELETE FROM dispatch_gateway_request"); }

    void table(String id, String key) {
        jdbc.execute("CREATE TABLE records(tenant_id VARCHAR(16) NOT NULL,company_code VARCHAR(16) NOT NULL,id " + id
                + ",doc_no VARCHAR(16),dispatched INT," + key + ") ENGINE=InnoDB");
    }
    StandardReportAdapter adapter() { return adapter(new NamedParameterJdbcTemplate(jdbc)); }
    StandardReportAdapter adapter(NamedParameterJdbcTemplate template) {
        return new StandardReportAdapter(new StandardQueryConfig("records", "id", "company_code", "tenant_id", "doc_no", "number",
                null,null,null,"dispatched",0,1,null,List.of(),List.of()), template);
    }
    DispatchGateway.Outcome dispatch(StandardReportAdapter adapter) {
        var report = spy(new TestCatalog().get(TestCatalog.SALES)); doReturn(adapter).when(report).adapter(); doReturn(adapter.fields()).when(report).fields();
        var gateway = new MockDispatchGateway(jdbc, mock(RuleCache.class), new RuleEngine());
        return tx.execute(status -> gateway.dispatch(new DispatchGateway.DispatchRequest("T001","request",report,
                com.example.report.rule.DispatchCandidateService.toCandidate(report,adapter.pendingRowsByIds("T001",List.of("7")).get(0),null,null,null,null),false)));
    }

    @Test void companyScopedCompositeKeyIsRejectedAtPublishReadAndWriteWithoutMutatingAnyCompany() {
        table("INT", "PRIMARY KEY(tenant_id,company_code,id)");
        jdbc.update("INSERT INTO records VALUES ('T001','A',7,'SO-A',0),('T001','B',7,'SO-B',0)");
        var adapter = adapter();
        assertThrows(ApiException.class, adapter::probe);
        assertThrows(ApiException.class, () -> adapter.pendingRows("T001", Set.of("A")));
        assertThrows(ApiException.class, () -> adapter.rowsByIds("T001", List.of("7")));
        assertThrows(ApiException.class, () -> dispatch(adapter));
        assertEquals(0, jdbc.queryForObject("SELECT SUM(dispatched) FROM records", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_gateway_request", Integer.class));
    }

    @Test void tenantScopedUniqueIdsAreSupportedAndOtherTenantIsUntouched() {
        table("INT", "PRIMARY KEY(tenant_id,id)");
        jdbc.update("INSERT INTO records VALUES ('T001','A',7,'SO-A',0),('T002','B',7,'SO-B',0)");
        var adapter = adapter(); adapter.probe();
        assertTrue(dispatch(adapter).success());
        assertEquals(1, jdbc.queryForObject("SELECT dispatched FROM records WHERE tenant_id='T001'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT dispatched FROM records WHERE tenant_id='T002'", Integer.class));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM dispatch_gateway_request", String.class));
    }

    @Test void schemaChangeAfterProbeCannotBypassRuntimeIdentityCheck() {
        table("INT NOT NULL", "PRIMARY KEY(id)");
        var adapter = adapter(); adapter.probe();
        jdbc.execute("ALTER TABLE records DROP PRIMARY KEY, ADD PRIMARY KEY(tenant_id,company_code,id)");
        jdbc.update("INSERT INTO records VALUES ('T001','A',7,'SO-A',0),('T001','B',7,'SO-B',0)");
        assertThrows(ApiException.class, () -> dispatch(adapter));
        assertEquals(0, jdbc.queryForObject("SELECT SUM(dispatched) FROM records", Integer.class));
    }

    @Test void nullableUniqueIdIsRejected() {
        table("INT NULL", "UNIQUE KEY(id)");
        assertThrows(ApiException.class, () -> adapter().probe());
    }

    @Test void nonUniqueIndexDoesNotProveIdentity() {
        table("INT NOT NULL", "KEY(id)");
        assertThrows(ApiException.class, () -> adapter().probe());
    }

    @Test void abnormalAffectedRowCountRollsBackSourceAndGatewayRequest() {
        table("INT NOT NULL", "PRIMARY KEY(id)");
        jdbc.update("INSERT INTO records VALUES ('T001','A',7,'SO-A',0)");
        var template = spy(new NamedParameterJdbcTemplate(jdbc));
        doAnswer(call -> { call.callRealMethod(); return 2; }).when(template).update(anyString(), any(SqlParameterSource.class));
        assertThrows(IllegalStateException.class, () -> dispatch(adapter(template)));
        assertEquals(0, jdbc.queryForObject("SELECT dispatched FROM records", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_gateway_request", Integer.class));
    }

    @Test void guardedUpdateBindsCompanyAndRequiresTransaction() {
        table("INT NOT NULL", "PRIMARY KEY(id)");
        jdbc.update("INSERT INTO records VALUES ('T001','A',7,'SO-A',0)");
        var template = spy(new NamedParameterJdbcTemplate(jdbc));
        assertTrue(dispatch(adapter(template)).success());
        verify(template).update(contains("AND `company_code` = :company"), argThat((SqlParameterSource p) -> "A".equals(p.getValue("company"))));
        assertThrows(IllegalStateException.class, () -> adapter().markDispatchedGuarded("T001", "7", "A", LocalDateTime.now(), row -> true));
    }
}
