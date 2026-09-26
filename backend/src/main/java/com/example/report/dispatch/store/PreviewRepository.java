package com.example.report.dispatch.store;

import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 预览快照的持久化。状态迁移一律是带原状态条件的更新（CAS），返回是否迁移成功。
 */
public interface PreviewRepository {

    /** 在当前事务里锁住会话，串行化同一会话的状态变更 */
    void lockConversation(String conversationId);

    void insert(DispatchPreview preview, List<DispatchPreviewItem> items);

    Optional<DispatchPreview> find(String previewId);

    List<DispatchPreview> findAll(Collection<String> previewIds);

    List<DispatchPreviewItem> items(String previewId);

    /** 本会话最近一次预览（不论状态），用于在上一轮范围上追加 / 排除报表 */
    Optional<DispatchPreview> latest(String tenantId, String userId, String conversationId);

    /** 本会话当前 ACTIVE 的预览 */
    List<DispatchPreview> active(String conversationId);

    List<DispatchPreview> byConversation(String conversationId);

    boolean transition(String previewId, String fromStatus, String toStatus, String reason, LocalDateTime now);
}
