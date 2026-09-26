package com.example.report.conversation;

import java.time.LocalDateTime;

/**
 * 链路追溯用的消息：含工具调用与工具结果，不含卡片载荷（载荷另有预览 / 清单记录可查）
 */
public record TraceMessage(Long id, String role, String toolName, String content, String cardType, String previewId,
                           String planId, LocalDateTime createdAt) {
}
