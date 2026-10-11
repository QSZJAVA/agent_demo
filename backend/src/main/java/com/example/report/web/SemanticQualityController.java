package com.example.report.web;

import com.example.report.assistant.*;
import com.example.report.common.Result;
import com.example.report.permission.PermissionService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 语义诊断、用户反馈与业务词典入口；身份只从当前认证会话解析，业务范围由服务端逐次核验。 */
@RestController
@RequestMapping("/api/semantic-quality")
public class SemanticQualityController {
    private final PermissionService permissions;
    private final AssistantQualityService quality;
    private final SemanticDictionaryService dictionaries;
    public SemanticQualityController(PermissionService permissions,AssistantQualityService quality,SemanticDictionaryService dictionaries){this.permissions=permissions;this.quality=quality;this.dictionaries=dictionaries;}
    @GetMapping("/runs") public Result<?> runs(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam(required=false) Long before){return Result.ok(quality.list(permissions.resolve(user),before));}
    @GetMapping("/runs/{id}") public Result<?> run(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable long id){return Result.ok(quality.get(permissions.resolve(user),id));}
    @GetMapping("/runs/{id}/feedback") public Result<?> feedback(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable long id,@RequestParam(required=false) Long before){return Result.ok(quality.feedback(permissions.resolve(user),id,before));}
    @PostMapping("/runs/{id}/feedback") public Result<?> feedback(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable long id,@RequestBody Map<String,Object> body){return Result.ok(quality.submitFeedback(permissions.resolve(user),id,body));}
    @GetMapping("/catalog") public Result<?> catalog(@RequestHeader(PermissionService.USER_HEADER) String user){return Result.ok(dictionaries.catalog(permissions.resolve(user)));}
    @GetMapping("/dictionary") public Result<?> dictionary(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam BusinessQuery.Domain domain,@RequestParam String companyCode,@RequestParam String reportId){return Result.ok(dictionaries.read(permissions.resolve(user),new SemanticDictionary.Scope(domain,companyCode,reportId)));}
    @GetMapping("/dictionary/history") public Result<?> history(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam BusinessQuery.Domain domain,@RequestParam String companyCode,@RequestParam String reportId){return Result.ok(dictionaries.history(permissions.resolve(user),new SemanticDictionary.Scope(domain,companyCode,reportId)));}
    /** 只校验草稿，不发布配置或调用模型。 */
    @PostMapping("/dictionary/validate") public Result<?> validate(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam BusinessQuery.Domain domain,@RequestParam String companyCode,@RequestParam String reportId,@RequestBody Map<String,Object> body){return Result.ok(dictionaries.validate(permissions.resolve(user),new SemanticDictionary.Scope(domain,companyCode,reportId),SemanticDictionaryService.decodeChange(body)));}
    /** 管理员在当前业务范围内显式发布，版本冲突交回用户重新读取。 */
    @PutMapping("/dictionary") public Result<?> publish(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam BusinessQuery.Domain domain,@RequestParam String companyCode,@RequestParam String reportId,@RequestBody Map<String,Object> body){return Result.ok(dictionaries.publish(permissions.resolve(user),new SemanticDictionary.Scope(domain,companyCode,reportId),SemanticDictionaryService.decodeChange(body)));}
}
