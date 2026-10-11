package com.example.report.web;

import com.example.report.common.*;
import com.example.report.permission.PermissionService;
import com.example.report.workspace.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 查询工作区、导出与站内提醒接口；身份只来自认证会话，结构化表单严格解码，所有入口逐次重新授权。 */
@RestController
@RequestMapping("/api/query-workspace")
public class QueryWorkspaceController {
    private final PermissionService permissions;private final QueryWorkspaceService workspace;private final QueryExportService exports;private final TaskInboxService inbox;
    public QueryWorkspaceController(PermissionService permissions,QueryWorkspaceService workspace,QueryExportService exports,TaskInboxService inbox){this.permissions=permissions;this.workspace=workspace;this.exports=exports;this.inbox=inbox;}
    @GetMapping("/runs") public Result<?> runs(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam(required=false) String before){return Result.ok(workspace.list(permissions.resolve(user),before));}
    @GetMapping("/runs/{id}") public Result<?> page(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestParam(defaultValue="1") int page){return Result.ok(workspace.page(permissions.resolve(user),id,page));}
    @DeleteMapping("/runs/{id}") public Result<?> remove(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id){workspace.expire(permissions.resolve(user),id);return Result.ok(Map.of("expired",true));}
    @PostMapping("/runs/{id}/saved") public Result<?> save(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestBody Map<String,Object> input){return Result.ok(workspace.save(permissions.resolve(user),id,decode(input,QueryWorkspaceService.Save.class)));}
    @PostMapping("/runs/{id}/analysis") public Result<?> analyze(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestBody Map<String,Object> input){return Result.ok(workspace.analyze(permissions.resolve(user),id,decode(input,QueryAnalysis.Request.class)));}
    @PostMapping("/runs/{id}/rank") public Result<?> rank(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestBody Map<String,Object> input){return Result.ok(workspace.rank(permissions.resolve(user),id,decode(input,QueryAnalysis.Rank.class)));}
    @PostMapping("/runs/{id}/rank-plan") public Result<?> rankPlan(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestBody Map<String,Object> input){return Result.ok(workspace.prepareRank(permissions.resolve(user),id,decode(input,QueryAnalysis.Rank.class)));}
    @GetMapping("/compare") public Result<?> compare(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam String before,@RequestParam String after){return Result.ok(workspace.compare(permissions.resolve(user),before,after));}
    @GetMapping("/saved") public Result<?> saved(@RequestHeader(PermissionService.USER_HEADER) String user){return Result.ok(workspace.savedList(permissions.resolve(user)));}
    @PostMapping("/saved/{id}/run") public Result<?> run(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id){return Result.ok(workspace.runSaved(permissions.resolve(user),id));}
    @PutMapping("/saved/{id}") public Result<?> update(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestParam long version,@RequestBody Map<String,Object> input){return Result.ok(workspace.updateSaved(permissions.resolve(user),id,version,decode(input,QueryWorkspaceService.Save.class)));}
    @DeleteMapping("/saved/{id}") public Result<?> delete(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestParam long version){workspace.deleteSaved(permissions.resolve(user),id,version);return Result.ok(Map.of("deleted",true));}
    @PostMapping("/runs/{id}/exports") public Result<?> export(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return Result.ok(exports.submit(permissions.resolve(user),id,key));}
    @GetMapping("/exports/{id}") public Result<?> export(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id){return Result.ok(exports.get(permissions.resolve(user),id));}
    @PostMapping("/exports/{id}/cancel") public Result<?> cancel(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id){return Result.ok(exports.cancel(permissions.resolve(user),id));}
    /** 文件只经已认证请求获取，不能用公开URL或查询串令牌分享；缓存与MIME嗅探关闭。 */
    @GetMapping("/exports/{id}/file") public ResponseEntity<byte[]> download(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id){
        if(!id.matches("[a-zA-Z0-9_-]{1,64}"))throw new ApiException(422,"导出标识无效");byte[] bytes=exports.download(permissions.resolve(user),id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"query-"+id+".csv\"").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).body(bytes);
    }
    @GetMapping("/inbox") public Result<?> inbox(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam(defaultValue="ALL") String kind,@RequestParam(defaultValue="false") boolean unread,@RequestParam(required=false) String before){return Result.ok(inbox.list(permissions.resolve(user),kind,unread,before));}
    @PostMapping("/inbox/read") public Result<?> read(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestBody Map<String,Object> input){var request=decode(input,Read.class);inbox.read(permissions.resolve(user),request.kind(),request.id(),request.eventKey());return Result.ok(Map.of("read",true));}
    /**
     * 只标记用户实际见过的任务状态。
     * @param kind 任务类型白名单值
     * @param id 本人任务标识
     * @param eventKey 页面展示的当前状态摘要，状态变化时拒绝
     */
    public record Read(String kind,String id,String eventKey) {
        public Read { if(!Set.of("DISPATCH","PLAN","ATTENTION","INVESTIGATION","EXPORT").contains(Objects.toString(kind,""))
                || id==null || !id.matches("[a-zA-Z0-9_-]{1,64}") || eventKey==null || !eventKey.matches("[a-f0-9]{64}"))throw new ApiException(422,"提醒标记需要有效任务类型、标识和状态摘要"); }
    }
    private static <T>T decode(Object value,Class<T> type){try{return JsonUtil.strictMapper().readValue(JsonUtil.toJson(value),type);}catch(Exception error){for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof ApiException api)throw api;throw new ApiException(422,"工作区参数的字段、类型或范围不符合协议");}}
}
