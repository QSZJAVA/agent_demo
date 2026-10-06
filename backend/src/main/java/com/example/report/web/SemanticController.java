package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.permission.PermissionService;
import com.example.report.semantic.SemanticConversationService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 读写当前用户所属会话的权威选择状态；手动选择通过比较条件和租约保护，刷新恢复同一集合，不能执行派单。
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
    /**
     * 手动选择请求；记录键必须属于previewId，expectedExclusions用于防止并发覆盖。
     * @param previewId 当前有效预览标识
     * @param expectedExclusions 修改前从服务端读取的排除集合
     * @param excludedRecords 本次希望持久化的完整排除集合，空集合表示全部选择
     */
    public record SelectionRequest(String previewId, java.util.List<com.example.report.dispatch.RecordKey> expectedExclusions,
            java.util.List<com.example.report.dispatch.RecordKey> excludedRecords) { }
    /** 保存当前用户自己的选择；仅操作有效预览，冲突需重新读取，不自动覆盖其他页面。 */
    @PutMapping("/{id}/selection")
    public Result<Map<String,Object>> updateSelection(@RequestHeader(PermissionService.USER_HEADER) String userId,@PathVariable String id,
            @RequestBody SelectionRequest request) {
        if(request==null)throw new com.example.report.common.ApiException(422,"选择参数缺失");
        return Result.ok(semantic.updateSelection(permissions.resolve(userId),id,request.previewId(),request.expectedExclusions(),request.excludedRecords()));
    }
}
