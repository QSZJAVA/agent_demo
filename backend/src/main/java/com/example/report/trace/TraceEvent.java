package com.example.report.trace;

import java.time.LocalDateTime;

/**
 * payload 为发生当时的不可变证据；delivery 字段仅描述展示表的同步情况。
 * @param id 不可变证据事件数据库主键
 * @param eventKey 稳定证据事件幂等键
 * @param tenantId 数据所属租户标识，来自服务端身份
 * @param userId 租户内用户标识，来自服务端身份
 * @param conversationId 用户所属会话标识；无会话的直接接口调用可为空
 * @param previewId 预览标识，选择和建单必须绑定此快照
 * @param planId 派单清单标识，关联服务端持久化清单
 * @param eventType 证据事件类型
 * @param payload 该业务类型的结构化载荷，持久化或展示前须脱敏
 * @param createdAt 记录创建时间
 * @param deliveryStatus PENDING待补写或DELIVERED已补写
 * @param deliveryAttempts 展示证据补写尝试次数
 */
public record TraceEvent(long id, String eventKey, String tenantId, String userId,
                         String conversationId, String previewId, String planId,
                         String eventType, String payload, LocalDateTime createdAt,
                         String deliveryStatus, int deliveryAttempts) {
    public static final String AUDIT = "AUDIT";
    public static final String MESSAGE = "MESSAGE";
    public static final String PLAN = "PLAN";
}
