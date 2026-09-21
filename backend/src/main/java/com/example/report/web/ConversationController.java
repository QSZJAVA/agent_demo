package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.conversation.ConversationService;
import com.example.report.conversation.MessageView;
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

/**
 * 历史会话：列表 / 历史消息 / 改名 / 删除。全部按登录态用户过滤，别人的会话返回 404。
 */
@RestController
@RequestMapping("/api/agent/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final PermissionService permissionService;

    public ConversationController(ConversationService conversationService, PermissionService permissionService) {
        this.conversationService = conversationService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public Result<List<AgentConversation>> list(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(conversationService.list(user, page, Math.min(Math.max(size, 1), 200)));
    }

    @GetMapping("/{id}/messages")
    public Result<List<MessageView>> messages(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                              @PathVariable String id,
                                              @RequestParam(required = false) Long beforeId,
                                              @RequestParam(defaultValue = "100") int size) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(conversationService.messages(user, id, beforeId, Math.min(Math.max(size, 1), 500)));
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
