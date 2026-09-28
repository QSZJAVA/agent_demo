package com.example.report.trace;

import java.time.LocalDateTime;

/** payload 为发生当时的不可变证据；delivery 字段仅描述展示表的同步情况。 */
public record TraceEvent(long id, String eventKey, String tenantId, String userId,
                         String conversationId, String previewId, String planId,
                         String eventType, String payload, LocalDateTime createdAt,
                         String deliveryStatus, int deliveryAttempts) {
    public static final String AUDIT = "AUDIT";
    public static final String MESSAGE = "MESSAGE";
    public static final String PLAN = "PLAN";
}
