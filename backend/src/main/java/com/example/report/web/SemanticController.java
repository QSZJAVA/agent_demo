package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.permission.PermissionService;
import com.example.report.semantic.SemanticConversationService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 读取当前用户所属会话的权威语义选择状态；用于刷新后恢复前端选中范围，不能通过该接口执行派单。
 */
@RestController
@RequestMapping("/api/agent/conversations")
public class SemanticController {
    private final PermissionService permissions;
    private final SemanticConversationService semantic;
    public SemanticController(PermissionService permissions, SemanticConversationService semantic) {
        this.permissions=permissions;this.semantic=semantic;
    }
    @GetMapping("/{id}/selection")
    public Result<Map<String,Object>> selection(@RequestHeader(PermissionService.USER_HEADER) String userId,@PathVariable String id) {
        return Result.ok(semantic.selection(permissions.resolve(userId),id));
    }
}
