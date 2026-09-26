package com.example.report.agent;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.PlanSnapshot;
import com.example.report.dispatch.PreviewOutcome;
import com.example.report.permission.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 不经过模型、由界面直接发起的操作（在报表选择卡片上选报表、通过接口生成清单）也要进入会话：
 * 卡片写进对话日志，历史记录里能看到；工作记忆里补一条系统记录，模型后续对话知道发生了什么。
 */
@Slf4j
@Component
public class ConversationCards {

    private final ConversationService conversationService;
    private final ChatMemory chatMemory;
    private final ReportCatalogService catalogService;

    public ConversationCards(ConversationService conversationService, ChatMemory chatMemory, ReportCatalogService catalogService) {
        this.conversationService = conversationService;
        this.chatMemory = chatMemory;
        this.catalogService = catalogService;
    }

    public PreviewPayload recordSelectionPreview(CurrentUser user, String conversationId, PreviewOutcome outcome) {
        PreviewPayload payload = PreviewPayload.of(outcome.snapshot(), catalogService);
        if (conversationId == null) {
            return payload;
        }
        String names = payload.byReport().stream().map(PreviewPayload.ReportCount::reportName).collect(Collectors.joining("、"));
        conversationService.logUser(conversationId, user.userId(), "（选择报表）" + names);
        conversationService.logCard(conversationId, user.userId(), "preview", payload, payload.previewId(), null);
        String counts = payload.byReport().stream().map(c -> c.reportName() + " " + c.count() + " 条")
                .collect(Collectors.joining("，"));
        remember(conversationId, "（系统记录）用户在报表选择卡片上选择了：" + names + "。已生成新的预览，共 "
                + payload.total() + " 条（" + counts + "），之前的预览和待确认清单已作废。");
        return payload;
    }

    public PlanPayload recordPlan(CurrentUser user, String conversationId, PlanSnapshot plan) {
        PlanPayload payload = PlanPayload.of(plan);
        if (conversationId == null) {
            return payload;
        }
        conversationService.logCard(conversationId, user.userId(), "plan", payload, plan.plan().getPreviewId(), plan.plan().getId());
        remember(conversationId, "（系统记录）已生成待确认的派单清单，共 " + payload.count() + " 条，等待用户在界面上确认。");
        return payload;
    }

    private void remember(String conversationId, String note) {
        try {
            chatMemory.add(conversationId, new AssistantMessage(note));
        } catch (RuntimeException e) {
            log.warn("工作记忆写入失败 conversation={}", conversationId, e);
        }
    }
}
