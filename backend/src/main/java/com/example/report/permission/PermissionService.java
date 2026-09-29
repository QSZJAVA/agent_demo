package com.example.report.permission;

import com.example.report.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 现有权限接口的模拟实现：真实系统替换这一层即可。
 * demo 用户都属于租户 T001：user1 只看 A 公司，user2 只看 B 公司，user3 看 B 公司但没有应收报表权限，
 * admin 看 A/B/C 公司、拥有全部报表权限，并可维护规则与报表目录。
 * 用户身份由请求头 X-User-Id 模拟登录态。
 */
@Service
public class PermissionService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.example.report.security.IdentityStore identities;
    public boolean isSecure() { return identities!=null; }

    public static final String USER_HEADER = "X-User-Id";
    public static final String DEMO_TENANT = "T001";

    private static final Set<String> ALL_DEMO_REPORTS = Set.of("report:sales", "report:receivable", "report:expense");
    private static final Map<String, CurrentUser> USERS = new LinkedHashMap<>();

    static {
        USERS.put("user1", new CurrentUser(DEMO_TENANT, "user1", "用户1（A 公司）", Set.of("A"), ALL_DEMO_REPORTS, false));
        USERS.put("user2", new CurrentUser(DEMO_TENANT, "user2", "用户2（B 公司）", Set.of("B"), ALL_DEMO_REPORTS, false));
        USERS.put("user3", new CurrentUser(DEMO_TENANT, "user3", "用户3（B 公司，无应收报表权限）", Set.of("B"),
                Set.of("report:sales", "report:expense"), false));
        USERS.put("admin", new CurrentUser(DEMO_TENANT, "admin", "管理员（A/B/C 公司，可改规则与报表目录）", Set.of("A", "B", "C"),
                Set.of(CurrentUser.ALL), true));
    }

    public CurrentUser resolve(String userId) {
        if(identities!=null) return identities.resolve(identities.tenant(),userId);
        if (userId == null || userId.isBlank()) {
            throw ApiException.forbidden("未登录：缺少请求头 " + USER_HEADER);
        }
        CurrentUser user = USERS.get(userId.trim());
        if (user == null) {
            throw ApiException.forbidden("未知用户：" + userId);
        }
        return user;
    }

    public List<CurrentUser> listUsers() {
        return List.copyOf(USERS.values());
    }

    public void requireAdmin(CurrentUser user) {
        if (!user.admin()) {
            throw ApiException.forbidden("只有管理员可以执行该操作");
        }
    }

    /**
     * 业务记录的归属校验：租户和用户都必须一致。不归属当前用户的记录按不存在处理（404），不暴露是否存在。
     */
    public static boolean owns(CurrentUser user, String tenantId, String userId) {
        return user != null && Objects.equals(user.tenantId(), tenantId) && Objects.equals(user.userId(), userId);
    }
}
