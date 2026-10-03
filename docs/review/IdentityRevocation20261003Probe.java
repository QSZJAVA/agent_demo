import com.example.report.security.*;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;

/** Real IdentityStore/transactions in a UUID database. No HTTP, model or business mutation. */
public class IdentityRevocation20261003Probe {
    static String setting(String key,String fallback) { return System.getenv().getOrDefault(key,fallback); }
    static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String schema="revocation_review_"+UUID.randomUUID().toString().replace("-", "");
        String base="jdbc:mysql://"+setting("DB_HOST","127.0.0.1")+":"+setting("DB_PORT","3306")+"/";
        var ds=new DriverManagerDataSource(base+schema+"?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",setting("DB_USERNAME","root"),setting("DB_PASSWORD",""));
        CountDownLatch targetReadReached=new CountDownLatch(1),resume=new CountDownLatch(1);
        var jdbc=spy(new JdbcTemplate(ds));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var executor=Executors.newSingleThreadExecutor(r->new Thread(r,"stale-admin-request"));
        try {
            require(schema.equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"unexpected database");
            for(String sql:Files.readString(Path.of("backend/src/main/resources/db/migration/V19__business_identity_and_dispatch.sql")).split(";")) if(!sql.isBlank()) jdbc.execute(sql);
            var store=new IdentityStore(jdbc,new ObjectMapper(),mock(StringRedisTemplate.class),"T001",UUID.randomUUID().toString());
            store.bootstrap();
            CurrentUser root=store.resolve("T001","admin");
            tx.executeWithoutResult(s->store.saveUser(root,new IdentityStore.UserForm("adminA","Admin A",UUID.randomUUID().toString(),Set.of("A"),Set.of("*"),true,true)));
            CurrentUser authenticatedA=store.resolve("T001","adminA");
            doAnswer(invocation->{
                if(Thread.currentThread().getName().equals("stale-admin-request")) {
                    targetReadReached.countDown();
                    require(resume.await(15,TimeUnit.SECONDS),"probe timeout");
                }
                return invocation.callRealMethod();
            }).when(jdbc).query(eq("SELECT password_hash FROM app_user WHERE tenant_id=? AND user_id=? FOR UPDATE"),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class));
            var request=executor.submit(()->tx.executeWithoutResult(s->store.saveUser(authenticatedA,new IdentityStore.UserForm("adminA","Admin A",null,Set.of("A"),Set.of("*"),true,true))));
            require(targetReadReached.await(15,TimeUnit.SECONDS),"request did not reach target read");
            tx.executeWithoutResult(s->store.saveUser(root,new IdentityStore.UserForm("adminA","Admin A",null,Set.of("A"),Set.of("report:sales"),false,true)));
            require(!store.resolve("T001","adminA").admin(),"demotion did not commit");
            resume.countDown();
            request.get(15,TimeUnit.SECONDS);
            require(store.resolve("T001","adminA").admin(),"stale request did not restore administrator");
            System.out.println("R3 REPRODUCED: an in-flight account update restores its actor's admin privilege AFTER another administrator's demotion commits");
        } finally {
            resume.countDown();executor.shutdownNow();
            if(schema.matches("revocation_review_[a-f0-9]{32}") && schema.equals(jdbc.queryForObject("SELECT DATABASE()",String.class))) jdbc.execute("DROP DATABASE `"+schema+"`");
        }
    }
}
