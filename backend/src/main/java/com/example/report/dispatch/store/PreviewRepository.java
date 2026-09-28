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

    /** Lock the source preview even when it has no conversation. */
    default void lockPreview(String previewId) { }

    /** Allocate the order of a preview request before its potentially long scan starts. */
    default long beginRequest(String conversationId) { return 0; }

    default boolean isLatestRequest(String conversationId, long version) { return true; }

    void insert(DispatchPreview preview, List<DispatchPreviewItem> items);

    default void insertBuilding(DispatchPreview preview) { throw new UnsupportedOperationException(); }

    default void appendItems(List<DispatchPreviewItem> items) { throw new UnsupportedOperationException(); }

    default void updateBuilding(DispatchPreview preview) { throw new UnsupportedOperationException(); }

    default void deleteBuilding(String previewId) { throw new UnsupportedOperationException(); }

    /** Cleanup rechecks both state and staleness while holding the activation row lock. */
    default void deleteBuildingBefore(String previewId, LocalDateTime cutoff) { throw new UnsupportedOperationException(); }

    Optional<DispatchPreview> find(String previewId);

    List<DispatchPreview> findAll(Collection<String> previewIds);

    List<DispatchPreviewItem> items(String previewId);

    default List<DispatchPreviewItem> page(String previewId, int offset, int size) {
        List<DispatchPreviewItem> all = items(previewId);
        if (offset >= all.size()) return List.of();
        return all.subList(offset, Math.min(all.size(), offset + size));
    }

    /** 本会话最近一次预览（不论状态），用于在上一轮范围上追加 / 排除报表 */
    Optional<DispatchPreview> latest(String tenantId, String userId, String conversationId);

    /** 本会话当前 ACTIVE 的预览 */
    List<DispatchPreview> active(String conversationId);

    List<DispatchPreview> byConversation(String conversationId);

    default List<DispatchPreview> byConversations(Collection<String> conversationIds) {
        return conversationIds.stream().flatMap(id -> byConversation(id).stream()).toList();
    }

    boolean transition(String previewId, String fromStatus, String toStatus, String reason, LocalDateTime now);
}
