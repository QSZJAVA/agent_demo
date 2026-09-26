package com.example.report.permission;

import com.example.report.common.Digests;

import java.util.Set;
import java.util.TreeSet;

/**
 * 当前登录用户：租户、身份、可见公司（组织 / 数据范围）、报表权限码、是否管理员。
 * 全部来自权限服务，模型永远接触不到，工具入参里也没有这些字段。
 *
 * @param permissions 报表级权限码（如 report:sales），{@value #ALL} 表示全部
 */
public record CurrentUser(String tenantId, String userId, String displayName, Set<String> companies,
                          Set<String> permissions, boolean admin) {

    public static final String ALL = "*";

    public CurrentUser {
        companies = companies == null ? Set.of() : Set.copyOf(companies);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean hasPermission(String code) {
        return code != null && !code.isBlank() && (permissions.contains(ALL) || permissions.contains(code));
    }

    /**
     * 权限版本：租户、公司范围、权限码、管理员标记任何一项变化都会变。
     * 预览快照记录它，生成清单和确认执行时比对，权限变化后旧预览、旧清单都不能再执行。
     */
    public String permissionVersion() {
        String canonical = tenantId + "|" + String.join(",", new TreeSet<>(companies)) + "|"
                + String.join(",", new TreeSet<>(permissions)) + "|" + admin;
        return Digests.sha256(canonical).substring(0, 32);
    }
}
