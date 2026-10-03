package com.example.report.dispatch.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;
import com.example.report.mapper.AgentConversationMapper;
import com.example.report.mapper.DispatchPreviewItemMapper;
import com.example.report.mapper.DispatchPreviewMapper;
import com.example.report.agent.PreviewPayload;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.JsonUtil;
import com.example.report.dispatch.PreviewSnapshot;
import com.example.report.entity.AgentMessage;
import com.example.report.trace.TraceJournal;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis 预览快照仓储；会话请求序号与唯一生成列共同保护预览激活。
 * 大量条目可分批写入 BUILDING 快照，只有完整结果且请求仍最新时才激活，避免展示半成品。
 */
@Repository
public class MybatisPreviewRepository implements PreviewRepository {

    /** 多行 INSERT 每批的行数，控制单条 SQL 的大小 */
    private static final int INSERT_CHUNK = 500;

    private final DispatchPreviewMapper previewMapper;
    private final DispatchPreviewItemMapper itemMapper;
    private final AgentConversationMapper conversationMapper;
    private final TraceJournal journal;
    private final ReportCatalogService catalog;

    public MybatisPreviewRepository(DispatchPreviewMapper previewMapper, DispatchPreviewItemMapper itemMapper,
                                    AgentConversationMapper conversationMapper, TraceJournal journal, ReportCatalogService catalog) {
        this.previewMapper = previewMapper;
        this.itemMapper = itemMapper;
        this.conversationMapper = conversationMapper;
        this.journal = journal;
        this.catalog = catalog;
    }

    @Override
    public void lockConversation(String conversationId) {
        if (conversationId != null) {
            conversationMapper.lockById(conversationId);
        }
    }

    @Override
    public void lockPreview(String previewId) {
        if (previewMapper.lockById(previewId) == null) {
            throw new IllegalStateException("预览不存在，不能创建或确认清单");
        }
    }

    @Override
    @Transactional
    public long beginRequest(String conversationId) {
        if (conversationId == null) return 0;
        if (conversationMapper.advancePreviewRequest(conversationId) != 1) {
            throw new IllegalStateException("会话不存在，无法开始预览");
        }
        return conversationMapper.previewRequestVersion(conversationId);
    }

    @Override
    public boolean isLatestRequest(String conversationId, long version) {
        return conversationId == null || java.util.Objects.equals(
                conversationMapper.previewRequestVersion(conversationId), version);
    }

    @Override
    @Transactional
    public void insert(DispatchPreview preview, List<DispatchPreviewItem> items) {
        previewMapper.insert(preview);
        for (int i = 0; i < items.size(); i += INSERT_CHUNK) {
            itemMapper.insertBatch(items.subList(i, Math.min(items.size(), i + INSERT_CHUNK)));
        }
        recordPreview(preview, items);
    }

    @Override
    public void insertBuilding(DispatchPreview preview) {
        previewMapper.insert(preview);
    }

    @Override
    @Transactional
    public void appendItems(List<DispatchPreviewItem> items) {
        if (items.isEmpty()) return;
        String previewId = items.get(0).getPreviewId();
        if (items.stream().anyMatch(item -> !previewId.equals(item.getPreviewId()))) {
            throw new IllegalArgumentException("预览批次不能混合不同快照");
        }
        DispatchPreview current = previewMapper.lockState(previewId);
        if (current == null || !DispatchPreview.BUILDING.equals(current.getStatus())) {
            throw new IllegalStateException("预览已停止构建，不能追加明细");
        }
        for (int i = 0; i < items.size(); i += INSERT_CHUNK) {
            itemMapper.insertBatch(items.subList(i, Math.min(items.size(), i + INSERT_CHUNK)));
        }
        previewMapper.update(null, new LambdaUpdateWrapper<DispatchPreview>().eq(DispatchPreview::getId, previewId)
                .eq(DispatchPreview::getStatus, DispatchPreview.BUILDING).set(DispatchPreview::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public void updateBuilding(DispatchPreview preview) {
        if (previewMapper.update(preview, new LambdaUpdateWrapper<DispatchPreview>()
                .eq(DispatchPreview::getId, preview.getId()).eq(DispatchPreview::getStatus, DispatchPreview.BUILDING)) != 1) {
            throw new IllegalStateException("预览汇总保存失败或已停止构建");
        }
    }

    @Override
    @Transactional
    public void deleteBuilding(String previewId) {
        deleteBuildingBefore(previewId, null);
    }

    @Override
    @Transactional
    public void deleteBuildingBefore(String previewId, LocalDateTime cutoff) {
        DispatchPreview current = previewMapper.lockState(previewId);
        if (current == null || !DispatchPreview.BUILDING.equals(current.getStatus())
                || (cutoff != null && !current.getUpdatedAt().isBefore(cutoff))) return;
        itemMapper.delete(new LambdaQueryWrapper<DispatchPreviewItem>().eq(DispatchPreviewItem::getPreviewId, previewId));
        previewMapper.delete(new LambdaQueryWrapper<DispatchPreview>()
                .eq(DispatchPreview::getId, previewId).eq(DispatchPreview::getStatus, DispatchPreview.BUILDING));
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
    public List<DispatchPreviewItem> page(String previewId, int offset, int size) {
        return itemMapper.selectList(new LambdaQueryWrapper<DispatchPreviewItem>()
                .eq(DispatchPreviewItem::getPreviewId, previewId)
                .orderByAsc(DispatchPreviewItem::getSeq)
                .last("LIMIT " + size + " OFFSET " + offset));
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
    public List<DispatchPreview> byConversations(Collection<String> conversationIds) {
        if (conversationIds.isEmpty()) return List.of();
        return previewMapper.selectList(new LambdaQueryWrapper<DispatchPreview>()
                .in(DispatchPreview::getConversationId, conversationIds));
    }

    @Override
    @Transactional
    public boolean transition(String previewId, String fromStatus, String toStatus, String reason, LocalDateTime now) {
        boolean changed = previewMapper.update(null, new LambdaUpdateWrapper<DispatchPreview>()
                .eq(DispatchPreview::getId, previewId)
                .eq(DispatchPreview::getStatus, fromStatus)
                .set(DispatchPreview::getStatus, toStatus)
                .set(DispatchPreview::getStatusReason, reason)
                .set(DispatchPreview::getUpdatedAt, now)) == 1;
        if (changed && DispatchPreview.ACTIVE.equals(toStatus)) {
            recordPreview(previewMapper.selectById(previewId), page(previewId, 0, 50));
        }
        return changed;
    }

    private void recordPreview(DispatchPreview preview, List<DispatchPreviewItem> items) {
        if (preview.getConversationId() == null) return;
        AgentMessage message = new AgentMessage();
        message.setTenantId(preview.getTenantId());
        message.setUserId(preview.getUserId());
        message.setConversationId(preview.getConversationId());
        message.setPreviewId(preview.getId());
        message.setRole(AgentMessage.ROLE_CARD);
        message.setCardType("preview");
        message.setPayload(JsonUtil.toJson(PreviewPayload.of(new PreviewSnapshot(preview, items), catalog)));
        message.setCreatedAt(preview.getCreatedAt());
        journal.message(message, false, "card:preview:" + preview.getId());
    }
}
