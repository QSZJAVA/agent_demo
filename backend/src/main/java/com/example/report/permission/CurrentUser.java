package com.example.report.permission;

import java.util.Set;

/**
 * 当前登录用户：身份、可见公司范围、是否管理员。全部来自权限服务，模型永远接触不到
 */
public record CurrentUser(String userId, String displayName, Set<String> companies, boolean admin) {
}
