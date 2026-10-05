package com.example.report.config;

import com.example.report.common.ApiException;
import com.example.report.common.Digests;
import com.example.report.common.JsonUtil;
import com.example.report.permission.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 数据库并发配额账本；同租户同操作用范围锁串行化容量检查，Redis丢键不会释放真实配额。
 * 采用独立事务，防止业务回滚恢复已释放租约；租约过期后不能续租复活。
 */
@Component
public class QuotaLeaseStore {
    static final int LEASE_SECONDS = 300;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public QuotaLeaseStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    /**
     * 独立事务取得操作范围锁并持久化租约；普通操作租户20个、用户4个，调查独立限制租户4个、用户2个。
     */
    public void acquire(CurrentUser user, String operation, String token) {
        String scope = Digests.sha256(JsonUtil.toJson(List.of(user.tenantId(), operation)));
        tx.executeWithoutResult(status -> {
            // A duplicate INSERT IGNORE takes a shared lock and can deadlock on the later
            // FOR UPDATE upgrade. This no-op upsert takes the exclusive scope lock directly.
            jdbc.update("INSERT INTO resource_quota_scope(scope_key) VALUES (?) ON DUPLICATE KEY UPDATE scope_key=scope_key", scope);
            jdbc.queryForObject("SELECT scope_key FROM resource_quota_scope WHERE scope_key=? FOR UPDATE", String.class, scope);
            jdbc.update("DELETE FROM resource_quota_lease WHERE scope_key=? AND expires_at<=NOW(6)", scope);
            List<String> users = jdbc.queryForList("SELECT user_id FROM resource_quota_lease WHERE scope_key=?", String.class, scope);
            int tenantLimit="investigation".equals(operation)?4:20, userLimit="investigation".equals(operation)?2:4;
            if (users.size() >= tenantLimit || users.stream().filter(user.userId()::equals).count() >= userLimit) {
                throw new ApiException(429, "同时运行的任务过多，请稍后重试");
            }
            jdbc.update("INSERT INTO resource_quota_lease(token,scope_key,user_id,expires_at) "
                    + "VALUES (?,?,?,TIMESTAMPADD(SECOND,?,NOW(6)))", token, scope, user.userId(), LEASE_SECONDS);
        });
    }

    /** Never resurrect a token whose slot could already belong to another request. */
    public boolean renew(String token) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.update("UPDATE resource_quota_lease "
                + "SET expires_at=TIMESTAMPADD(SECOND,?,NOW(6)) WHERE token=? AND expires_at>NOW(6)",
                LEASE_SECONDS, token) == 1));
    }

    /**
     * 幂等删除本令牌的租约；独立提交，外层业务事务回滚也不能恢复配额占用。
     */
    public void release(String token) {
        tx.executeWithoutResult(status -> jdbc.update("DELETE FROM resource_quota_lease WHERE token=?", token));
    }
}
