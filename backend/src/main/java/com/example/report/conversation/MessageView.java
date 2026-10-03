package com.example.report.conversation;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 历史消息 DTO：文本与卡片；工具消息不返回给前端。
 * 预览 / 清单卡片带上服务端当前状态（status / statusMessage），前端据此展示，不再自行推导是否有效。
 * @param id 消息自增主键，作为历史消息分页边界
 * @param role 消息角色：user、assistant、工具或卡片
 * @param content 消息文本或工具摘要，卡片数据另存payload
 * @param cardType preview、plan或result；普通消息为空
 * @param payload 该业务类型的结构化载荷，持久化或展示前须脱敏
 * @param previewId 预览标识，选择和建单必须绑定此快照
 * @param planId 派单清单标识，关联服务端持久化清单
 * @param createdAt 记录创建时间
 * @param status 卡片对应业务对象的当前服务端状态；普通文本可为空
 * @param statusMessage 服务端生成的状态说明
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
