package com.example.report.agent;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.PlanSnapshot;
import com.example.report.dispatch.PreviewOutcome;
import com.example.report.permission.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 不经过模型、由界面直接发起的操作（在报表选择卡片上选报表、通过接口生成清单）也要进入会话：
 * 卡片写入持久化会话日志；后续语义解析从当前权威状态及受限会话上下文读取。
 */
@Component
public class ConversationCards {

    private final ConversationService conversationService;
    private final ReportCatalogService catalogService;

    public ConversationCards(ConversationService conversationService, ReportCatalogService catalogService) {
        this.conversationService = conversationService;
        this.catalogService = catalogService;
    }

    /** 记录用户选定报表后的权威预览；无会话时仅返回载荷，持久卡片用于刷新恢复。 */
    public PreviewPayload recordSelectionPreview(CurrentUser user, String conversationId, PreviewOutcome outcome) {
        PreviewPayload payload = PreviewPayload.of(outcome.snapshot(), catalogService);
        if (conversationId == null) {
            return payload;
        }
        String names = payload.byReport().stream().map(PreviewPayload.ReportCount::reportName).collect(Collectors.joining("、"));
        conversationService.logUser(conversationId, user.userId(), "（选择报表）" + names);
        conversationService.logCard(conversationId, user.userId(), "preview", payload, payload.previewId(), null);
        return payload;
    }

    /** 异步查询成功后持久化当前预览卡片；快照激活和权限验证由预览服务完成。 */
    public PreviewPayload recordAsyncPreview(CurrentUser user, String conversationId, PreviewOutcome outcome) {
        PreviewPayload payload = PreviewPayload.of(outcome.snapshot(), catalogService);
        if (conversationId != null) {
            conversationService.logCard(conversationId, user.userId(), "preview", payload, payload.previewId(), null);
        }
        return payload;
    }

    /** 将已创建的待确认清单写入会话；不确认或执行派单，返回同一清单载荷。 */
    public PlanPayload recordPlan(CurrentUser user, String conversationId, PlanSnapshot plan) {
        PlanPayload payload = PlanPayload.of(plan);
        if (conversationId == null) {
            return payload;
        }
        conversationService.logCard(conversationId, user.userId(), "plan", payload, plan.plan().getPreviewId(), plan.plan().getId());
        return payload;
    }

}
