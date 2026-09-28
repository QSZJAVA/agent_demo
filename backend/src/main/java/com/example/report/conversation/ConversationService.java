package com.example.report.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.common.Digests;
import com.example.report.common.TraceIds;
import com.example.report.trace.TraceJournal;
import com.example.report.trace.TraceProjector;
import com.example.report.entity.AgentConversation;
import com.example.report.entity.AgentMessage;
import com.example.report.mapper.AgentConversationMapper;
import com.example.report.mapper.AgentMessageMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 对话日志与会话管理。消息先写可靠事件，历史展示表可以在故障恢复后按顺序幂等补写。
 */
@Slf4j
@Service
public class ConversationService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.report.dispatch.PreviewService previewService;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentProperties props;
    private final TraceJournal journal;
    private final TraceProjector projector;

    public ConversationService(AgentConversationMapper conversationMapper,
                               AgentMessageMapper messageMapper,
                               AgentProperties props,
                               TraceJournal journal, TraceProjector projector) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.props = props;
        this.journal = journal;
        this.projector = projector;
    }

    // ---------- 会话管理 ----------

    public AgentConversation create(CurrentUser user, String model) {
        LocalDateTime now = LocalDateTime.now();
        AgentConversation c = new AgentConversation();
        c.setId(JsonUtil.newId());
        c.setTenantId(user.tenantId());
        c.setUserId(user.userId());
        c.setModel(model);
        c.setMessageCount(0);
        c.setStatus(AgentConversation.STATUS_ACTIVE);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversationMapper.insert(c);
        return c;
    }

    /** 归属校验：租户和用户都必须一致，不是当前用户的会话按不存在处理（404），不暴露是否存在 */
    public AgentConversation getOwned(CurrentUser user, String conversationId) {
        AgentConversation c = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (c == null || !PermissionService.owns(user, c.getTenantId(), c.getUserId())
                || !AgentConversation.STATUS_ACTIVE.equals(c.getStatus())) {
            throw ApiException.notFound("会话不存在");
        }
        return c;
    }

    public List<AgentConversation> list(CurrentUser user, int page, int size) {
        int skip = Math.max(page - 1, 0) * size;
        if (previewService == null) return conversationPage(user, skip, size);
        List<AgentConversation> visible = new ArrayList<>(size);
        int scanned = 0;
        int allowed = 0;
        while (visible.size() < size) {
            List<AgentConversation> batch = conversationPage(user, scanned, 200);
            if (batch.isEmpty()) break;
            java.util.Set<String> readable = previewService.readableConversationIds(user,
                    batch.stream().map(AgentConversation::getId).toList());
            for (AgentConversation c : batch) {
                if (readable.contains(c.getId())) {
                    if (allowed++ >= skip) visible.add(c);
                    if (visible.size() == size) break;
                }
            }
            scanned += batch.size();
            if (batch.size() < 200) break;
        }
        return visible;
    }

    private List<AgentConversation> conversationPage(CurrentUser user, int offset, int size) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getTenantId, user.tenantId())
                .eq(AgentConversation::getUserId, user.userId())
                .eq(AgentConversation::getStatus, AgentConversation.STATUS_ACTIVE)
                .orderByDesc(AgentConversation::getLastMessageAt)
                .orderByDesc(AgentConversation::getCreatedAt)
                .orderByDesc(AgentConversation::getId)
                .last("LIMIT " + offset + ", " + size));
    }

    public void rename(CurrentUser user, String conversationId, String title) {
        AgentConversation c = getOwned(user, conversationId);
        if (title == null || title.isBlank()) {
            throw new ApiException("标题不能为空");
        }
        c.setTitle(truncate(title.trim(), 64));
        c.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(c);
    }

    public void softDelete(CurrentUser user, String conversationId) {
        AgentConversation c = getOwned(user, conversationId);
        c.setStatus(AgentConversation.STATUS_DELETED);
        c.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(c);
    }

    /** 历史消息：向前翻页，只返回文本与卡片 */
    public List<MessageView> messages(CurrentUser user, String conversationId, Long beforeId, int size) {
        getOwned(user, conversationId);
        if (previewService != null) previewService.requireConversationReadable(user, conversationId);
        List<AgentMessage> list = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTenantId, conversationTenant(conversationId))
                .eq(AgentMessage::getConversationId, conversationId)
                .in(AgentMessage::getRole, AgentMessage.ROLE_USER, AgentMessage.ROLE_ASSISTANT, AgentMessage.ROLE_CARD)
                .lt(beforeId != null, AgentMessage::getId, beforeId)
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT " + size));
        Collections.reverse(list);
        List<MessageView> views = new ArrayList<>(list.size());
        for (AgentMessage m : list) {
            views.add(new MessageView(m.getId(), m.getRole(), m.getContent(), m.getCardType(),
                    m.getPayload() == null ? null : JsonUtil.toMap(m.getPayload()),
                    m.getPreviewId(), m.getPlanId(), m.getCreatedAt(), null, null));
        }
        return views;
    }

    /**
     * 链路追溯：从触发预览的那条用户消息开始，到清单执行结束为止的全部消息（含工具调用与工具结果，不含卡片载荷）。
     * 卡片是异步落库的，截止时间留几秒余量。
     */
    public List<TraceMessage> traceMessages(String conversationId, LocalDateTime from, LocalDateTime until) {
        List<AgentMessage> trigger = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTenantId, conversationTenant(conversationId))
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getRole, AgentMessage.ROLE_USER)
                .le(AgentMessage::getCreatedAt, from)
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT 1"));
        List<AgentMessage> list = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTenantId, conversationTenant(conversationId))
                .eq(AgentMessage::getConversationId, conversationId)
                .ge(!trigger.isEmpty(), AgentMessage::getId, trigger.isEmpty() ? null : trigger.get(0).getId())
                .ge(trigger.isEmpty(), AgentMessage::getCreatedAt, from)
                .le(AgentMessage::getCreatedAt, until.plusSeconds(5))
                .orderByAsc(AgentMessage::getId)
                .last("LIMIT 500"));
        return list.stream().map(m -> new TraceMessage(m.getId(), m.getRole(), m.getToolName(), truncate(m.getContent(), 2000),
                m.getCardType(), m.getPreviewId(), m.getPlanId(), m.getCreatedAt())).toList();
    }

    /** 工作记忆回灌用：最近 N 条用户 / 助手文本消息（按时间正序） */
    public List<AgentMessage> recentTextMessages(String conversationId, int limit) {
        List<AgentMessage> list = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTenantId, conversationTenant(conversationId))
                .eq(AgentMessage::getConversationId, conversationId)
                .in(AgentMessage::getRole, AgentMessage.ROLE_USER, AgentMessage.ROLE_ASSISTANT)
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT " + limit));
        Collections.reverse(list);
        return list;
    }

    private String conversationTenant(String conversationId) {
        AgentConversation owner = conversationMapper.selectById(conversationId);
        if (owner == null) throw ApiException.notFound("会话不存在");
        return owner.getTenantId();
    }

    // ---------- 对话日志写入（异步） ----------

    public void logUser(String conversationId, String userId, String text) {
        AgentMessage m = base(conversationId, userId, AgentMessage.ROLE_USER);
        m.setContent(text);
        submit(m, true);
    }

    public void logAssistant(String conversationId, String userId, String text, String model, Usage usage, long latencyMs) {
        AgentMessage m = base(conversationId, userId, AgentMessage.ROLE_ASSISTANT);
        m.setContent(text);
        m.setModel(model);
        if (usage != null) {
            m.setPromptTokens(usage.getPromptTokens());
            m.setCompletionTokens(usage.getCompletionTokens());
        }
        m.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
        submit(m, false);
    }

    public void logToolCall(String conversationId, String userId, String toolName, Object args) {
        AgentMessage m = base(conversationId, userId, AgentMessage.ROLE_TOOL_CALL);
        m.setToolName(toolName);
        m.setContent(JsonUtil.toJson(args));
        submit(m, false);
    }

    public void logToolResult(String conversationId, String userId, String toolName, String summary, String previewId, String planId) {
        AgentMessage m = base(conversationId, userId, AgentMessage.ROLE_TOOL_RESULT);
        m.setToolName(toolName);
        m.setContent(summary);
        m.setPreviewId(previewId);
        m.setPlanId(planId);
        submit(m, false);
    }

    /** 结构化卡片只保存摘要与首屏；全部条目留在分页快照表中。 */
    public void logCard(String conversationId, String userId, String cardType, Object payload, String previewId, String planId) {
        AgentMessage m = base(conversationId, userId, AgentMessage.ROLE_CARD);
        m.setCardType(cardType);
        m.setPayload(JsonUtil.toJson(capPayload(payload)));
        m.setPreviewId(previewId);
        m.setPlanId(planId);
        submit(m, false);
    }

    private Object capPayload(Object payload) {
        int max = Math.min(props.getConversation().getCardPayloadMaxRows(), 50);
        Map<String, Object> map = JsonUtil.toMap(JsonUtil.toJson(payload));
        Map<String, Object> copy = new java.util.LinkedHashMap<>(map);
        for (String field : List.of("records", "success", "failed")) {
            Object value = copy.get(field);
            if (value instanceof List<?> list && list.size() > max) {
                copy.put(field, list.subList(0, max));
                copy.put(field + "Truncated", true);
            }
        }
        return copy;
    }

    private AgentMessage base(String conversationId, String userId, String role) {
        AgentMessage m = new AgentMessage();
        m.setConversationId(conversationId);
        if (conversationId != null) {
            AgentConversation owner = conversationMapper.selectById(conversationId);
            if (owner == null || !java.util.Objects.equals(owner.getUserId(), userId)) {
                throw ApiException.notFound("会话不存在");
            }
            m.setTenantId(owner.getTenantId());
        }
        m.setUserId(userId);
        m.setTraceId(TraceIds.current());
        m.setRole(role);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private void submit(AgentMessage m, boolean maybeTitle) {
        if (m.getConversationId() == null) {
            return;
        }
        String key = "message:" + JsonUtil.newId();
        if (AgentMessage.ROLE_CARD.equals(m.getRole())) {
            // 同一预览/清单卡片重放不重复计数；结果变化产生新的证据。
            String identity = m.getPlanId() != null ? m.getPlanId() : m.getPreviewId();
            if (identity != null) {
                key = "card:" + m.getCardType() + ":" + identity;
                if ("result".equals(m.getCardType())) {
                    Map<String, Object> value = new java.util.LinkedHashMap<>(JsonUtil.toMap(m.getPayload()));
                    value.remove("replayed");
                    key += ":" + Digests.sha256(JsonUtil.toJson(value));
                }
            }
        }
        long eventId = journal.message(m, maybeTitle, key);
        projector.afterCommit(eventId);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
