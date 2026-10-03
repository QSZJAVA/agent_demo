package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.conversation.ConversationService;
import com.example.report.conversation.MessageView;
import com.example.report.dispatch.CardStateService;
import com.example.report.entity.AgentConversation;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 历史会话：列表 / 历史消息 / 改名 / 删除 / 卡片状态。全部按登录态用户（租户 + 用户）过滤，别人的会话返回 404。
 */
@RestController
@RequestMapping("/api/agent/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final PermissionService permissionService;
    private final CardStateService cardStateService;

    public ConversationController(ConversationService conversationService, PermissionService permissionService,
                                  CardStateService cardStateService) {
        this.conversationService = conversationService;
        this.permissionService = permissionService;
        this.cardStateService = cardStateService;
    }

    @GetMapping
    public Result<List<AgentConversation>> list(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(conversationService.list(user, page, Math.min(Math.max(size, 1), 200)));
    }

    /** 历史消息：预览 / 清单卡片带上服务端当前状态，刷新页面或换设备后与服务端一致 */
    @GetMapping("/{id}/messages")
    public Result<List<MessageView>> messages(@RequestHeader(PermissionService.USER_HEADER) String userId,
    @PathVariable String id,
                                              @RequestParam(required = false) Long beforeId,
                                              @RequestParam(defaultValue = "100") int size) {
        CurrentUser user = permissionService.resolve(userId);
        List<MessageView> views = conversationService.messages(user, id, beforeId, Math.min(Math.max(size, 1), 500));
        Map<String, CardStateService.CardState> previews = cardStateService.previewStates(user, views.stream()
                .filter(v -> "preview".equals(v.cardType())).map(MessageView::previewId).filter(Objects::nonNull).toList());
        Map<String, CardStateService.CardState> plans = cardStateService.planStates(user, views.stream()
                .filter(v -> "plan".equals(v.cardType())).map(MessageView::planId).filter(Objects::nonNull).toList());
        return Result.ok(views.stream().map(v -> {
            CardStateService.CardState state = "preview".equals(v.cardType()) ? previews.get(v.previewId())
                    : "plan".equals(v.cardType()) ? plans.get(v.planId()) : null;
            return state == null ? v : v.withState(state.status(), state.message());
        }).toList());
    }

    /** 会话里全部预览 / 清单卡片的当前状态：前端每轮对话、每次操作后以此为准刷新卡片*/
    @GetMapping("/{id}/card-states")
    public Result<CardStateService.ConversationStates> cardStates(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                                  @PathVariable String id) {
        CurrentUser user = permissionService.resolve(userId);
        conversationService.getOwned(user, id);
        return Result.ok(cardStateService.conversationStates(user, id));
    }

    @PutMapping("/{id}/title")
    public Result<Void> rename(@RequestHeader(PermissionService.USER_HEADER) String userId,
                               @PathVariable String id,
                               @RequestBody Map<String, String> body) {
        CurrentUser user = permissionService.resolve(userId);
        conversationService.rename(user, id, body.get("title"));
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader(PermissionService.USER_HEADER) String userId, @PathVariable String id) {
        CurrentUser user = permissionService.resolve(userId);
        conversationService.softDelete(user, id);
        return Result.ok(null);
    }
}
