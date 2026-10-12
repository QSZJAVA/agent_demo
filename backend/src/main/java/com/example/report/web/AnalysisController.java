package com.example.report.web;

import com.example.report.analysis.*;
import com.example.report.common.Result;
import com.example.report.permission.PermissionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 分析与业务知识HTTP入口；身份只取认证会话，所有表单严格解码，读取与写入知识资料分别执行业务权限检查。 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {
    private final PermissionService permissions;private final AnalysisService analysis;private final KnowledgeService knowledge;private final ObjectProvider<AnalysisWorker> workers;
    public AnalysisController(PermissionService permissions,AnalysisService analysis,KnowledgeService knowledge,ObjectProvider<AnalysisWorker> workers){this.permissions=permissions;this.analysis=analysis;this.knowledge=knowledge;this.workers=workers;}
    @GetMapping("/catalog") public Result<?> catalog(@RequestHeader(PermissionService.USER_HEADER) String user){return Result.ok(knowledge.catalog(permissions.resolve(user)));}
    @GetMapping("/runs") public Result<?> runs(@RequestHeader(PermissionService.USER_HEADER) String user){return Result.ok(analysis.list(permissions.resolve(user)));}
    @PostMapping("/runs") public Result<?> submit(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestHeader("Idempotency-Key") String key,@RequestBody String input){return Result.ok(analysis.submit(permissions.resolve(user),AnalysisContract.decode(input,AnalysisContract.Request.class),key));}
    @GetMapping("/runs/{id}") public Result<?> get(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id){return Result.ok(analysis.get(permissions.resolve(user),id));}
    @PostMapping("/runs/{id}/cancel") public Result<?> cancel(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id){var result=analysis.cancel(permissions.resolve(user),id);var worker=workers.getIfAvailable();if(worker!=null)worker.stop(id);return Result.ok(result);}
    @GetMapping("/runs/{id}/steps/{step}/rows") public Result<?> rows(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@PathVariable int step,@RequestParam(defaultValue="1") int page){return Result.ok(analysis.page(permissions.resolve(user),id,step,page));}
    @GetMapping("/knowledge") public Result<?> documents(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestParam String companyCode,@RequestParam String reportId){return Result.ok(knowledge.list(permissions.resolve(user),companyCode,reportId));}
    /** 试查使用当前维护权限和服务端知识快照；严格拒绝客户端伪造的来源、时间、词典与预算。 */
    @PostMapping("/knowledge/search") public Result<?> search(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestBody String input){return Result.ok(knowledge.preview(permissions.resolve(user),AnalysisContract.decode(input,KnowledgeService.SearchRequest.class)));}
    @GetMapping("/knowledge/{id}/history") public Result<?> history(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id){return Result.ok(knowledge.history(permissions.resolve(user),id));}
    @PostMapping("/knowledge") public Result<?> create(@RequestHeader(PermissionService.USER_HEADER) String user,@RequestBody String input){return Result.ok(knowledge.save(permissions.resolve(user),null,AnalysisContract.decode(input,KnowledgeService.Change.class)));}
    @PutMapping("/knowledge/{id}") public Result<?> update(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestBody String input){return Result.ok(knowledge.save(permissions.resolve(user),id,AnalysisContract.decode(input,KnowledgeService.Change.class)));}
    @PostMapping("/knowledge/{id}/publish") public Result<?> publish(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestBody String input){return Result.ok(knowledge.transition(permissions.resolve(user),id,AnalysisContract.decode(input,KnowledgeService.Transition.class),true));}
    @PostMapping("/knowledge/{id}/disable") public Result<?> disable(@RequestHeader(PermissionService.USER_HEADER) String user,@PathVariable String id,@RequestBody String input){return Result.ok(knowledge.transition(permissions.resolve(user),id,AnalysisContract.decode(input,KnowledgeService.Transition.class),false));}
}
