package com.example.report.rule;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.report.common.ApiException;
import com.example.report.config.ResourceQuotaService;
import com.example.report.mapper.*;
import com.example.report.support.DispatchHarness;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "TRACE_IT", matches = "true")
class RulePublicationConcurrencyTest {
    static String schema;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static DispatchRuleMapper mapper;
    static RuleService service;
    static long catalogVersion;

    @BeforeAll static void database() throws Exception {
        schema = "rule_race_it_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:mysql://" + System.getenv().getOrDefault("TRACE_DB_HOST", "127.0.0.1")
                + ":" + System.getenv().getOrDefault("TRACE_DB_PORT", "3306") + "/" + schema
                + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("TRACE_DB_USER", "root"), System.getenv().getOrDefault("TRACE_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds); var manager = new DataSourceTransactionManager(ds); tx = new TransactionTemplate(manager);
        Flyway.configure().dataSource(ds).load().migrate();
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(DispatchRuleMapper.class); config.addMapper(DispatchRuleHistoryMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(factory.getObject()); mapper = sql.getMapper(DispatchRuleMapper.class);
        var h = new DispatchHarness();
        catalogVersion = jdbc.queryForObject("SELECT catalog_version FROM report_definition WHERE report_id=?",Long.class,SALES);
        h.catalog.replace(com.example.report.support.TestCatalog.with(h.catalog.get(SALES),catalogVersion,"published",true));
        var target = new RuleService(mapper,sql.getMapper(DispatchRuleHistoryMapper.class),new RuleEngine(),mock(RuleCache.class),
                mock(DispatchCandidateService.class),h.catalogService,mock(ResourceQuotaService.class));
        var proxy = new ProxyFactory(target); proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        service = (RuleService)proxy.getProxy();
    }
    @AfterAll static void cleanup() {
        if(jdbc!=null && schema!=null && schema.matches("rule_race_it_[a-f0-9]{32}")) {
            assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class)); jdbc.execute("DROP DATABASE `"+schema+"`");
        }
    }
    @BeforeEach void seed() {
        jdbc.update("UPDATE report_definition SET catalog_version=? WHERE report_id=?",catalogVersion,SALES);
        jdbc.update("DELETE FROM dispatch_rule_history"); jdbc.update("DELETE FROM dispatch_rule");
        for(int id=1;id<=3;id++) jdbc.update("INSERT INTO dispatch_rule(id,tenant_id,report_id,company_code,name,expression,version,status,created_at) "
                + "VALUES (?,'T001',?,'A','rule','amount > 20',?,?,NOW())",id,SALES,id,id==1?"published":"draft");
    }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout"); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    RuleService.RuleForm form(Long id) {
        var form=new RuleService.RuleForm(); form.setId(id); form.setReportId(SALES);form.setCompanyCode("A");form.setExpression("amount > 999");return form;
    }

    @Test void concurrentPublishLeavesExactlyOnePublishedRuleEvenWithOlderReadViews() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);
        try {
            List<Future<?>> jobs=new ArrayList<>();
            for(long id:List.of(2L,3L)) jobs.add(pool.submit(()->tx.execute(status->{
                mapper.selectById(id);ready.countDown();await(ready);service.publish(ADMIN,id);return null;
            })));
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_rule WHERE status='published'",Integer.class));
        }finally{pool.shutdownNow();}
    }

    @Test void staleDraftEditorCannotOverwriteARulePublishedWhileItWasWaiting() throws Exception {
        var pool=Executors.newSingleThreadExecutor();var read=new CountDownLatch(1);var published=new CountDownLatch(1);
        try {
            var writer=pool.submit(()->assertThrows(ApiException.class,()->tx.execute(status->{
                mapper.selectById(2L);read.countDown();await(published);
                service.saveDraft(ADMIN,form(2L));return null;
            })));
            assertTrue(read.await(5,TimeUnit.SECONDS));service.publish(ADMIN,2L);published.countDown();writer.get(10,TimeUnit.SECONDS);
            assertEquals("published",jdbc.queryForObject("SELECT status FROM dispatch_rule WHERE id=2",String.class));
            assertEquals("amount > 20",jdbc.queryForObject("SELECT expression FROM dispatch_rule WHERE id=2",String.class));
        }finally{published.countDown();pool.shutdownNow();}
    }

    @Test void concurrentDraftCreationAllocatesDistinctIncreasingVersions() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);
        try {
            List<Future<Integer>> jobs=new ArrayList<>();
            for(int i=0;i<2;i++) jobs.add(pool.submit(()->tx.execute(status->{
                mapper.selectById(1L);ready.countDown();await(ready);return service.saveDraft(ADMIN,form(null)).getVersion();
            })));
            Set<Integer> versions=new HashSet<>();for(var job:jobs)versions.add(job.get(10,TimeUnit.SECONDS));
            assertEquals(Set.of(4,5),versions);
        }finally{pool.shutdownNow();}
    }

    @Test void rulePublicationRejectsAStaleDirectorySnapshot() {
        jdbc.update("UPDATE report_definition SET catalog_version=catalog_version+1 WHERE report_id=?",SALES);
        assertEquals(409,assertThrows(ApiException.class,()->service.publish(ADMIN,2L)).getCode());
        assertEquals("draft",jdbc.queryForObject("SELECT status FROM dispatch_rule WHERE id=2",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_rule WHERE status='published'",Integer.class));
    }
}
