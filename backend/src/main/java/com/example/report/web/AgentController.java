package com.example.report.web;

import com.example.report.agent.AgentChatService;
import com.example.report.agent.PreviewPayload;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.ApiException;
import com.example.report.common.Result;
import com.example.report.dispatch.PreviewService;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Agent 对话入口（SSE）与预览快照读取
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentChatService chatService;
    private final PermissionService permissionService;
    private final PreviewService previewService;
    private final ReportCatalogService catalogService;

    public AgentController(AgentChatService chatService, PermissionService permissionService, PreviewService previewService,
                           ReportCatalogService catalogService) {
        this.chatService = chatService;
        this.permissionService = permissionService;
        this.previewService = previewService;
        this.catalogService = catalogService;
    }

    /**
     * 对话：POST JSON，返回 text/event-stream。
     * 事件：conversation / text / preview / choice / plan / result / error / done
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chat(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                              @RequestBody ChatRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ApiException("消息不能为空");
        }
        if (request.getMessage().length() > 2000) throw new ApiException("消息不能超过 2000 字");
        return chatService.chat(user, request.getConversationId(), request.getMessage().trim(),
                request.getPreviewId(), request.getExcludedRecords());
    }

    /** 预览快照全量记录与当前状态（前端按 previewId 拉取渲染表格） */
    @GetMapping("/previews/{previewId}")
    public Result<PreviewPayload> preview(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                          @PathVariable String previewId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(PreviewPayload.of(previewService.getOwned(user, previewId), catalogService));
    }

    @GetMapping("/model")
    public Result<Map<String, String>> model() {
        return Result.ok(Map.of("model", chatService.modelName()));
    }

    /** 本轮对话输入；身份从服务端解析，记录选择必须绑定来源预览，不能由模型提供执行授权。 */
    @Data
    public static class ChatRequest {
        /** 用户所属会话标识；首次发送时为空，由服务端创建新会话。 */
        private String conversationId;
        /** 本轮用户原文；按接口长度边界校验后交由语义解析。 */
        private String message;
        /** 按报表与记录复合标识保存的排除项；服务端验证全部属于当前预览。 */
        private List<com.example.report.dispatch.RecordKey> excludedRecords;
        /** 取消勾选所在的预览卡片；与本轮派单用的预览不一致时勾选项不生效 */
        private String previewId;
    }
}
