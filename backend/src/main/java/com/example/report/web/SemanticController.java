package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.permission.PermissionService;
import com.example.report.semantic.SemanticConversationService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

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
