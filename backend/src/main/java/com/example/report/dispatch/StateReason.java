package com.example.report.dispatch;

import java.util.Map;

/**
 * 预览 / 清单状态变化的原因编码与业务提示。前端只负责展示，文案由服务端给出。
 */
public final class StateReason {

    public static final String NEW_PREVIEW = "NEW_PREVIEW";
    public static final String NEW_PLAN = "NEW_PLAN";
    public static final String TTL = "TTL";
    public static final String RULE_CHANGED = "RULE_CHANGED";
    public static final String CATALOG_CHANGED = "CATALOG_CHANGED";
    public static final String PERMISSION_CHANGED = "PERMISSION_CHANGED";
    public static final String EXECUTED = "EXECUTED";
    public static final String USER_CANCELLED = "USER_CANCELLED";
    public static final String EXECUTION_INTERRUPTED = "EXECUTION_INTERRUPTED";

    private static final Map<String, String> MESSAGES = Map.of(
            NEW_PREVIEW, "同一会话已生成新的预览，请使用最新卡片",
            NEW_PLAN, "同一会话已生成新的待确认清单，请使用最新卡片",
            TTL, "已超过有效期，请重新查询",
            RULE_CHANGED, "派单规则已更新，请重新查询",
            CATALOG_CHANGED, "报表目录已变更，请重新查询",
            PERMISSION_CHANGED, "权限范围已变化，请重新查询",
            EXECUTED, "已据此执行派单",
            USER_CANCELLED, "已取消",
            EXECUTION_INTERRUPTED, "执行结果待核对，请联系管理员按清单和外部请求号核对，勿重复派单");

    private StateReason() {
    }

    public static String message(String reason) {
        return reason == null ? null : MESSAGES.getOrDefault(reason, reason);
    }
}
