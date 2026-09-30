import com.example.report.security.*;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;

/** Isolated database probe. No model, business dispatch, or running application is used. */
public class IdentityReview20260930V2Probe {
    static String setting(String key,String fallback) { return System.getenv().getOrDefault(key,fallback); }
    static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String schema="identity_review_"+UUID.randomUUID().toString().replace("-", "");
        String base="jdbc:mysql://"+setting("DB_HOST","127.0.0.1")+":"+setting("DB_PORT","3306")+"/";
        var ds=new DriverManagerDataSource(base+schema+"?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",setting("DB_USERNAME","root"),setting("DB_PASSWORD",""));
        CountDownLatch insertReached=new CountDownLatch(1), resume=new CountDownLatch(1);
        var jdbc=new JdbcTemplate(ds) {
            @Override public int update(String sql,Object... values) {
                if(sql.startsWith("INSERT INTO app_session")) {
                    insertReached.countDown();
                    try { if(!resume.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("probe timeout"); }
                    catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                }
                return super.update(sql,values);
            }
        };
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var executor=Executors.newSingleThreadExecutor();
        try {
            require(schema.equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"unexpected database");
            for(String sql:Files.readString(Path.of("backend/src/main/resources/db/migration/V19__business_identity_and_dispatch.sql")).split(";")) if(!sql.isBlank()) jdbc.execute(sql);
            var redis=mock(StringRedisTemplate.class);
            ValueOperations<String,String> values=mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);when(values.increment(anyString())).thenReturn(1L);
            String oldPassword=UUID.randomUUID().toString(),newPassword=UUID.randomUUID().toString();
            var store=new IdentityStore(jdbc,new ObjectMapper(),redis,"T001",oldPassword);
            store.bootstrap();
            CurrentUser admin=store.resolve("T001","admin");
            tx.executeWithoutResult(s->store.saveUser(admin,new IdentityStore.UserForm("reader","Reader",oldPassword,Set.of("A"),Set.of("report:sales"),false,true)));
            var login=executor.submit(()->store.login("reader",oldPassword,"192.0.2.1"));
            require(insertReached.await(15,TimeUnit.SECONDS),"login did not reach session insertion");
            tx.executeWithoutResult(s->store.saveUser(admin,new IdentityStore.UserForm("reader","Reader",newPassword,Set.of("A"),Set.of("report:sales"),false,true)));
            resume.countDown();
            var result=login.get(15,TimeUnit.SECONDS);
            require("reader".equals(store.authenticate(result.token()).userId()),"race did not reproduce");
            System.out.println("R1 REPRODUCED: old-password login inserts a valid session AFTER password reset and session revocation commit");
            String collation=jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.columns WHERE table_schema=? AND table_name='app_user' AND column_name='user_id'",String.class,schema);
            tx.executeWithoutResult(s->store.saveUser(admin,new IdentityStore.UserForm("ADMIN","Administrator",null,Set.of("A","B","C"),Set.of("*"),false,false)));
            int enabled=jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE is_admin=true AND enabled=true",Integer.class);
            require(enabled==0,"case-variant self disable did not reproduce");
            System.out.println("R2 REPRODUCED: case-variant self-disable succeeds; enabled administrators=0; user_id collation="+collation);
        } finally {
            resume.countDown();executor.shutdownNow();
            if(schema.matches("identity_review_[a-f0-9]{32}") && schema.equals(jdbc.queryForObject("SELECT DATABASE()",String.class))) jdbc.execute("DROP DATABASE `"+schema+"`");
        }
    }
}
