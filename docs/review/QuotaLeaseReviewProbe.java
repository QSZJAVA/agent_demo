import com.example.report.config.ResourceQuotaService;
import com.example.report.permission.CurrentUser;
import com.example.report.common.ApiException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Historical pre-M1 diagnostic, for the former Redis-only ResourceQuotaService constructor.
 * Fixed-code acceptance is QuotaLeaseDatabaseTest / ResourceQuotaServiceTest.
 * This source is preserved as historical evidence and is not compatible with the new constructor.
 * Real Redis diagnostic; only four keys under a random synthetic tenant are touched.
 * Success means lost-lease handling is missing, not business acceptance passing. */
public class QuotaLeaseReviewProbe {
    public static void main(String[] args) throws Exception {
        var config = new RedisStandaloneConfiguration(System.getenv().getOrDefault("REVIEW_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("REVIEW_REDIS_PORT", "6379")));
        String password = System.getenv("REVIEW_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) config.setPassword(password);
        var factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        var quotas = new ResourceQuotaService(redis);
        String tenant = "quota_review_" + UUID.randomUUID().toString().replace("-", "");
        var user = new CurrentUser(tenant, "probe", "probe", Set.of(), Set.of(), false);
        String base = "quota:dispatch:" + tenant + ":";
        var active = List.of(base + "active:user:probe", base + "active:tenant");
        var allKeys = List.of(base + "minute:user:probe", base + "minute:tenant", active.get(0), active.get(1));
        var permits = new ArrayList<ResourceQuotaService.Permit>();
        try {
            for (int i = 0; i < 4; i++) permits.add(quotas.acquire(user, "dispatch", List.of()));
            assertEquals(429, assertThrows(ApiException.class, () -> quotas.acquire(user, "dispatch", List.of())).getCode());
            // Simulate ONLY this synthetic tenant losing its lease keys. Never restart or flush shared Redis.
            redis.delete(active);
            var renew = ResourceQuotaService.Permit.class.getDeclaredMethod("renew");
            renew.setAccessible(true);
            for (var permit : permits) renew.invoke(permit);
            assertEquals(0L, redis.opsForZSet().zCard(active.get(0)));
            var closed = ResourceQuotaService.Permit.class.getDeclaredField("closed");
            closed.setAccessible(true);
            for (var permit : permits) assertFalse(closed.getBoolean(permit));
            for (int i = 0; i < 4; i++) permits.add(quotas.acquire(user, "dispatch", List.of()));
            assertEquals(8, permits.size());
            assertEquals(4L, redis.opsForZSet().zCard(active.get(0)));
            System.out.println("M1 reproduced on Redis: limit=4; original 4 permits remain open after lease loss and renewal; 4 more permits granted, total open=8.");
        } finally {
            permits.forEach(ResourceQuotaService.Permit::close);
            quotas.shutdown();
            if (!tenant.matches("quota_review_[a-f0-9]{32}")) throw new IllegalStateException("unexpected test tenant");
            redis.delete(allKeys);
            factory.destroy();
        }
    }
}
