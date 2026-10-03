package com.example.report.conversation;

import java.time.LocalDateTime;

/**
 * 链路追溯用的消息：含工具调用与工具结果，不含卡片载荷（载荷另有预览 / 清单记录可查）
 * @param id 会话追溯消息主键
 * @param role 消息角色：user、assistant、工具或卡片
 * @param toolName 工具名称，普通消息为空
 * @param content 消息文本或工具摘要，卡片数据另存payload
 * @param cardType preview、plan或result；普通消息为空
 * @param previewId 预览标识，选择和建单必须绑定此快照
 * @param planId 派单清单标识，关联服务端持久化清单
 * @param createdAt 记录创建时间
 */
public record TraceMessage(Long id, String role, String toolName, String content, String cardType, String previewId,
                           String planId, LocalDateTime createdAt) {
}
