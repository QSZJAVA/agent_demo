package com.example.report.dispatch.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;
import com.example.report.mapper.AgentConversationMapper;
import com.example.report.mapper.DispatchPreviewItemMapper;
import com.example.report.mapper.DispatchPreviewMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisPreviewRepository implements PreviewRepository {

    /** 多行 INSERT 每批的行数，控制单条 SQL 的大小 */
    private static final int INSERT_CHUNK = 500;

    private final DispatchPreviewMapper previewMapper;
    private final DispatchPreviewItemMapper itemMapper;
    private final AgentConversationMapper conversationMapper;

    public MybatisPreviewRepository(DispatchPreviewMapper previewMapper, DispatchPreviewItemMapper itemMapper,
                                    AgentConversationMapper conversationMapper) {
        this.previewMapper = previewMapper;
        this.itemMapper = itemMapper;
        this.conversationMapper = conversationMapper;
    }

    @Override
    public void lockConversation(String conversationId) {
        if (conversationId != null) {
            conversationMapper.lockById(conversationId);
        }
    }

    @Override
    public void insert(DispatchPreview preview, List<DispatchPreviewItem> items) {
        previewMapper.insert(preview);
        for (int i = 0; i < items.size(); i += INSERT_CHUNK) {
            itemMapper.insertBatch(items.subList(i, Math.min(items.size(), i + INSERT_CHUNK)));
        }
    }

    @Override
    public Optional<DispatchPreview> find(String previewId) {
        return previewId == null ? Optional.empty() : Optional.ofNullable(previewMapper.selectById(previewId));
    }

    @Override
    public List<DispatchPreview> findAll(Collection<String> previewIds) {
        return previewIds.isEmpty() ? List.of() : previewMapper.selectByIds(previewIds);
    }

    @Override
    public List<DispatchPreviewItem> items(String previewId) {
        return itemMapper.selectList(new LambdaQueryWrapper<DispatchPreviewItem>()
                .eq(DispatchPreviewItem::getPreviewId, previewId)
                .orderByAsc(DispatchPreviewItem::getSeq));
    }

    @Override
    public Optional<DispatchPreview> latest(String tenantId, String userId, String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        // 同一毫秒内生成的两份预览 created_at 相同：以 ACTIVE 的那份为准（新预览生成时旧预览已被置为 SUPERSEDED）
        return previewMapper.selectList(new LambdaQueryWrapper<DispatchPreview>()
                        .eq(DispatchPreview::getTenantId, tenantId)
                        .eq(DispatchPreview::getUserId, userId)
                        .eq(DispatchPreview::getConversationId, conversationId)
                        .last("ORDER BY (status = 'ACTIVE') DESC, created_at DESC LIMIT 1"))
                .stream().findFirst();
    }

    @Override
    public List<DispatchPreview> active(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        return previewMapper.selectList(new LambdaQueryWrapper<DispatchPreview>()
                .eq(DispatchPreview::getConversationId, conversationId)
                .eq(DispatchPreview::getStatus, DispatchPreview.ACTIVE));
    }

    @Override
    public List<DispatchPreview> byConversation(String conversationId) {
        return previewMapper.selectList(new LambdaQueryWrapper<DispatchPreview>()
                .eq(DispatchPreview::getConversationId, conversationId)
                .orderByAsc(DispatchPreview::getCreatedAt));
    }

    @Override
    public boolean transition(String previewId, String fromStatus, String toStatus, String reason, LocalDateTime now) {
        return previewMapper.update(null, new LambdaUpdateWrapper<DispatchPreview>()
                .eq(DispatchPreview::getId, previewId)
                .eq(DispatchPreview::getStatus, fromStatus)
                .set(DispatchPreview::getStatus, toStatus)
                .set(DispatchPreview::getStatusReason, reason)
                .set(DispatchPreview::getUpdatedAt, now)) == 1;
    }
}
