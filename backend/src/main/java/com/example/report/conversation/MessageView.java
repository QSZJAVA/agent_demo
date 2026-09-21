package com.example.report.conversation;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 历史消息 DTO：文本与卡片；工具消息不返回给前端
 */
public record MessageView(
        Long id,
        String role,
        String content,
        String cardType,
        Map<String, Object> payload,
        String previewId,
        String planId,
        LocalDateTime createdAt
) {
}
