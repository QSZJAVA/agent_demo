import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.report.dispatch.*;
import com.example.report.dispatch.store.*;
import com.example.report.mapper.*;
import com.example.report.support.DispatchHarness;
import com.example.report.trace.*;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import java.util.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;

/** Historical pre-O1 diagnostic; fixed-code acceptance is TracePersistenceIntegrationTest.
 * Actual MySQL commit, followed by an injected acknowledgement failure; no business database. */
public class PreviewCommitReviewProbe {
    static <T> T transactional(T target, PlatformTransactionManager manager, Class<T> contract) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return contract.cast(factory.getProxy());
    }
    public static void main(String[] args) throws Exception {
        String schema = "review_o_commit_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        var jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        var tx = new TransactionTemplate(manager);
        try {
            Flyway.configure().dataSource(ds).load().migrate();
            jdbc.update("INSERT INTO agent_conversation(id,tenant_id,user_id,status,created_at,updated_at) VALUES ('c1','T001','user1','active',NOW(),NOW())");
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            for (Class<?> mapper : List.of(DispatchPlanMapper.class, DispatchPlanItemMapper.class, DispatchPreviewMapper.class,
                    DispatchPreviewItemMapper.class, AgentConversationMapper.class)) configuration.addMapper(mapper);
            var sqlFactory = new MybatisSqlSessionFactoryBean(); sqlFactory.setDataSource(ds); sqlFactory.setConfiguration(configuration);
            var sql = new SqlSessionTemplate(sqlFactory.getObject());
            var h = new DispatchHarness().put(SALES, candidate(SALES,"1","SO1","A","one"), candidate(SALES,"2","SO2","A","two"));
            h.props.getPreview().setMaxItems(1); // Force the existing large-preview branch with only two synthetic rows.
            var journal = new TraceJournal(jdbc, tx);
            var previews = transactional(new MybatisPreviewRepository(sql.getMapper(DispatchPreviewMapper.class),
                    sql.getMapper(DispatchPreviewItemMapper.class), sql.getMapper(AgentConversationMapper.class), journal, h.catalogService), manager, PreviewRepository.class);
            var plans = transactional(new MybatisPlanRepository(sql.getMapper(DispatchPlanMapper.class),
                    sql.getMapper(DispatchPlanItemMapper.class), journal, new RuleEvidence(jdbc)), manager, PlanRepository.class);
            TransactionOperations lostAcknowledgement = new TransactionOperations() {
                @Override public <T> T execute(TransactionCallback<T> action) {
                    T result = tx.execute(action); // Commit really succeeds in MySQL.
                    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_preview WHERE status='ACTIVE'", Integer.class));
                    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_preview_item", Integer.class));
                    throw new TransactionSystemException("Injected lost commit acknowledgement after successful commit");
                }
            };
            var service = new PreviewService(h.catalogService, h.candidates, h.versions, previews, plans, h.props, lostAcknowledgement);
            assertThrows(TransactionSystemException.class, () -> service.preview(USER1, "c1",
                    new PreviewCommand(null,"api",null,List.of(SALES),null,null,null)));
            var state = jdbc.queryForMap("SELECT status,total_count FROM dispatch_preview");
            int remaining = jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_preview_item", Integer.class);
            assertEquals("ACTIVE", state.get("status"));
            assertEquals(2, ((Number)state.get("total_count")).intValue());
            assertEquals(0, remaining);
            System.out.println("O1 reproduced: activation committed with 2 rows; acknowledgement error triggered cleanup; ACTIVE header total_count=2 remains, detail rows=0.");
        } finally {
            if (!schema.matches("review_o_commit_[a-f0-9]{32}")) throw new IllegalStateException("unexpected schema");
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            jdbc.execute("DROP DATABASE `" + schema + "`");
        }
    }
}
