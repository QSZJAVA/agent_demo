package com.example.report.conversation;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 历史消息 DTO：文本与卡片；工具消息不返回给前端。
 * 预览 / 清单卡片带上服务端当前状态（status / statusMessage），前端据此展示，不再自行推导是否有效。
 */
public record MessageView(
        Long id,
        String role,
        String content,
        String cardType,
        Map<String, Object> payload,
        String previewId,
        String planId,
        LocalDateTime createdAt,
        String status,
        String statusMessage
) {

    public MessageView withState(String status, String statusMessage) {
        return new MessageView(id, role, content, cardType, payload, previewId, planId, createdAt, status, statusMessage);
    }
}
