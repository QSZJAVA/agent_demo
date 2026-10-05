package com.example.report.web;

import com.example.report.common.*;
import com.example.report.investigation.*;
import com.example.report.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Set;

/** 真实会话的调查任务HTTP入口；POST幂等接收、GET只读恢复，业务错误使用对应HTTP状态。 */
@RestController
@RequestMapping("/api/investigations")
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationController {
    private final PermissionService permissions;
    private final InvestigationService service;
    public InvestigationController(PermissionService permissions,InvestigationService service) {this.permissions=permissions;this.service=service;}
    /** 当前请求只接收清单、条目和问题，拒绝未知字段以及客户端身份或预算覆盖。 */
    @PostMapping public ResponseEntity<Result<InvestigationService.Submission>> submit(@RequestHeader(PermissionService.USER_HEADER) String user,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input) {
        if(!input.isObject() || !input.hasNonNull("planId") || !input.hasNonNull("question")) throw new ApiException("调查请求格式无效");
        input.fieldNames().forEachRemaining(field -> {if(!Set.of("planId","itemIds","question").contains(field)) throw new ApiException("调查请求包含未允许字段");});
        if(!input.get("planId").isTextual() || !input.get("question").isTextual() || (input.hasNonNull("itemIds") && (!input.get("itemIds").isArray() || java.util.stream.StreamSupport.stream(input.get("itemIds").spliterator(),false).anyMatch(n -> !n.isTextual())))) throw new ApiException("调查参数类型无效");
        var request=JsonUtil.MAPPER.convertValue(input,InvestigationTypes.Request.class);var result=service.submit(permissions.resolve(user),request,key);
        return ResponseEntity.status(result.replayed() || result.empty()?200:202).body(Result.ok(result));
    }
    @GetMapping("/{id}") public Result<InvestigationTypes.Run> get(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id) {return Result.ok(service.get(permissions.resolve(user),id));}
    @GetMapping public Result<InvestigationTypes.Page> list(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam String planId,@RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="20") int size) {return Result.ok(service.list(permissions.resolve(user),planId,cursor,size));}
    @GetMapping("/candidates") public Result<InvestigationTypes.Page> candidates(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam String planId,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="20") int size) {return Result.ok(service.candidates(permissions.resolve(user),planId,afterId,size));}
    @GetMapping("/{id}/steps") public Result<InvestigationTypes.Page> steps(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestParam(defaultValue="0") long afterSeq,@RequestParam(defaultValue="20") int size) {return Result.ok(service.steps(permissions.resolve(user),id,afterSeq,size));}
    @GetMapping("/{id}/evidence/{ref}") public Result<Object> evidence(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@PathVariable String ref) {return Result.ok(service.evidence(permissions.resolve(user),id,ref));}
    @PostMapping("/{id}/cancel") public Result<InvestigationTypes.Run> cancel(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id) {return Result.ok(service.cancel(permissions.resolve(user),id));}
    @ExceptionHandler(ApiException.class) public ResponseEntity<Result<Void>> business(ApiException e) {return ResponseEntity.status(e.getCode()).body(Result.fail(e.getCode(),e.getMessage()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) public ResponseEntity<Result<Void>> malformed(Exception e) {return ResponseEntity.badRequest().body(Result.fail(400,"调查参数格式无效"));}
}
