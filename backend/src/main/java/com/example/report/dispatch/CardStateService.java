package com.example.report.dispatch;

import com.example.report.dispatch.store.PlanRepository;
import com.example.report.dispatch.store.PreviewRepository;
import com.example.report.common.ApiException;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPreview;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡片状态：预览 / 清单卡片的状态只由服务端给出（刷新页面、重新打开会话、换设备都一致），前端不再按消息下标推导。
 * 状态和成功/失败计数同样属于业务事实；先验证当前公司、报表及会话权限，再做惰性失效并返回本人卡片。
 */
@Service
public class CardStateService {

    private final PreviewService previewService;
    private final PlanService planService;
    private final PreviewRepository previews;
    private final PlanRepository plans;

    public CardStateService(PreviewService previewService, PlanService planService, PreviewRepository previews,
                            PlanRepository plans) {
        this.previewService = previewService;
        this.planService = planService;
        this.previews = previews;
        this.plans = plans;
    }

    /**
     * @param message 状态说明（失效原因等），前端直接展示
     * @param status 当前业务状态，以所属状态机为准
     * @param reason 操作原因或状态变更说明；保存前脱敏
     */
    public record CardState(String status, String reason, String message) {
    }

    /**
     * 会话内业务卡片的当前权威状态。
     * @param previews 以预览标识为键的权威状态集合
     * @param plans 清单或以清单标识为键的权威状态集合
     */
    public record ConversationStates(Map<String, CardState> previews, Map<String, CardState> plans) {
    }

    /** 按当前归属和业务范围读取预览状态；无权读取的历史快照不能只通过状态接口继续展示。 */
    public Map<String, CardState> previewStates(CurrentUser user, Collection<String> previewIds) {
        return previewStates(user, previews.findAll(previewIds));
    }

    /** 所有清单状态均重新验证来源快照权限，包括无需惰性失效的执行中和终态清单。 */
    public Map<String, CardState> planStates(CurrentUser user, Collection<String> planIds) {
        return planStates(user, plans.findAll(planIds));
    }

    /** 调用方先校验会话归属；此处统一复核整个会话的当前可读性，包含仅有查询事实的会话。 */
    public ConversationStates conversationStates(CurrentUser user, String conversationId) {
        previewService.requireConversationReadable(user, conversationId);
        return new ConversationStates(previewStates(user, previews.byConversation(conversationId)),
                planStates(user, plans.byConversation(conversationId)));
    }

    private Map<String, CardState> previewStates(CurrentUser user, List<DispatchPreview> list) {
        Map<String, CardState> states = new LinkedHashMap<>();
        for (DispatchPreview p : list) {
            if (!PermissionService.owns(user, p.getTenantId(), p.getUserId())) {
                continue;
            }
            // 终态预览不会再走版本校验，不能把 refresh 当成历史读取权限检查。
            previewService.requireReadable(user, p);
            DispatchPreview current = previewService.refresh(user, p);
            states.put(current.getId(), new CardState(current.getStatus(), current.getStatusReason(), previewMessage(current)));
        }
        return states;
    }

    private Map<String, CardState> planStates(CurrentUser user, List<DispatchPlan> list) {
        Map<String, CardState> states = new LinkedHashMap<>();
        for (DispatchPlan p : list) {
            if (!PermissionService.owns(user, p.getTenantId(), p.getUserId())) {
                continue;
            }
            DispatchPreview source = previews.find(p.getPreviewId())
                    .orElseThrow(() -> ApiException.notFound("原预览不存在"));
            previewService.requireReadable(user, source);
            DispatchPlan current = planService.refresh(user, p);
            states.put(current.getId(), new CardState(current.getStatus(), current.getStatusReason(), planMessage(current)));
        }
        return states;
    }

    static String previewMessage(DispatchPreview p) {
        return switch (p.getStatus()) {
            case DispatchPreview.ACTIVE -> null;
            case DispatchPreview.SUPERSEDED -> "该预览已作废，请使用最新预览";
            case DispatchPreview.CONSUMED -> "已据此执行派单";
            default -> StateReason.message(p.getStatusReason());
        };
    }

    static String planMessage(DispatchPlan p) {
        return switch (p.getStatus()) {
            case DispatchPlan.PENDING -> null;
            case DispatchPlan.EXECUTING -> "正在执行，请稍后刷新";
            case DispatchPlan.EXECUTED -> "已执行：成功 " + p.getSuccessCount() + " 条，失败 " + p.getFailedCount() + " 条";
            case DispatchPlan.CANCELLED -> "已取消";
            default -> StateReason.message(p.getStatusReason());
        };
    }
}
