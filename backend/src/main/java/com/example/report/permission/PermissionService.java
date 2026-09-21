package com.example.report.permission;

import com.example.report.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 现有权限接口的模拟实现：真实系统替换这一层即可。
 * demo 用户：user1 只看 A 公司，user2 只看 B 公司，admin 看 A/B/C 且可管理规则。
 * 用户身份由请求头 X-User-Id 模拟登录态。
 */
@Service
public class PermissionService {

    public static final String USER_HEADER = "X-User-Id";

    private static final Map<String, CurrentUser> USERS = new LinkedHashMap<>();

    static {
        USERS.put("user1", new CurrentUser("user1", "用户1（A 公司）", Set.of("A"), false));
        USERS.put("user2", new CurrentUser("user2", "用户2（B 公司）", Set.of("B"), false));
        USERS.put("admin", new CurrentUser("admin", "管理员（A/B/C 公司，可改规则）", Set.of("A", "B", "C"), true));
    }

    public CurrentUser resolve(String userId) {
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
            throw ApiException.forbidden("只有管理员可以维护派单规则");
        }
    }
}
