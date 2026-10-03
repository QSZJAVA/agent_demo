package com.example.report.web;

import com.example.report.catalog.*;
import com.example.report.common.*;
import com.example.report.operations.*;
import com.example.report.permission.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 租户管理与运维 HTTP 入口；统一要求管理员授权和操作原因，业务服务进一步校验数据范围并保存审计。
 */
@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final PermissionService permissions;
    private final OperationsPolicy policies;
    private final OperationsAudit audit;
    private final BusinessMetrics metrics;
    private final OperationsWorkbench workbench;
    private final DataRetentionService retention;
    private final CatalogRevisions revisions;
    private final ReportCatalogAdminService catalogAdmin;
    private final ReportCatalog catalog;
    private final ResolverEvaluation evaluation;
    @org.springframework.beans.factory.annotation.Autowired
    private ResolverEvaluationMonitor evaluationMonitor;
    public OperationsController(PermissionService permissions,OperationsPolicy policies,OperationsAudit audit,
            BusinessMetrics metrics,OperationsWorkbench workbench,DataRetentionService retention,CatalogRevisions revisions,
            ReportCatalogAdminService catalogAdmin,ReportCatalog catalog,ResolverEvaluation evaluation) {
        this.permissions=permissions;this.policies=policies;this.audit=audit;this.metrics=metrics;this.workbench=workbench;
        this.retention=retention;this.revisions=revisions;this.catalogAdmin=catalogAdmin;this.catalog=catalog;this.evaluation=evaluation;
    }
    private CurrentUser admin(String id) { var u=permissions.resolve(id);permissions.requireAdmin(u);return u; }
    @GetMapping("/metrics")
    public Result<?> metrics(@RequestHeader(PermissionService.USER_HEADER) String id,@RequestParam(defaultValue="7") int days) {
        var u=admin(id);
        if(days<1||days>90) throw new ApiException("统计窗口为 1～90 天");
        audit.record(u,"METRICS_READ","metrics","SUCCESS",null);
        return Result.ok(Map.of("samples",metrics.summary(u,days),"dispatch",metrics.dispatch(u,days),"overview",metrics.overview(u,days)));
    }
    @GetMapping("/audit")
    public Result<?> audit(@RequestHeader(PermissionService.USER_HEADER) String id,@RequestParam(defaultValue="0") long before) {
        var u=admin(id);var rows=audit.list(u,before);audit.record(u,"AUDIT_READ","audit","SUCCESS",null);return Result.ok(rows);
    }
    @GetMapping("/policies/{key}")
    public Result<?> policy(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String key) {
        var u=admin(id);OperationsPolicy.validateKey(key);return Result.ok(policies.get(u.tenantId(),key));
    }
    @PutMapping("/policies/{key}")
    public Result<?> savePolicy(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String key,@RequestBody OperationsPolicy.Change change) {
        return Result.ok(policies.save(admin(id),key,change));
    }
    @GetMapping("/policies/{key}/history")
    public Result<?> policyHistory(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String key) {
        return Result.ok(policies.history(admin(id),key));
    }
    /**
     * 以当前版本为条件的历史版本回滚请求。
     * @param targetVersion 要回滚的历史版本号；回滚创建新版本而非覆盖历史
     * @param expectedVersion 修改前读取的版本，须与服务器当前版本匹配
     * @param reason 操作原因或状态变更说明；保存前脱敏
     */
    public record Rollback(long targetVersion,long expectedVersion,String reason) { }
    @PostMapping("/policies/{key}/rollback")
    public Result<?> rollbackPolicy(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String key,@RequestBody Rollback r) {
        return Result.ok(policies.rollback(admin(id),key,r.targetVersion(),r.expectedVersion(),r.reason()));
    }
    @GetMapping("/catalog/{report}/history")
    public Result<?> catalogHistory(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String report) {
        return Result.ok(revisions.list(admin(id),report));
    }
    @PostMapping("/catalog/{report}/rollback")
    public Result<?> rollbackCatalog(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String report,@RequestBody Rollback r) {
        var u=admin(id);OperationsPolicy.requireReason(r.reason());
        var result=catalogAdmin.rollback(u,report,r.targetVersion(),r.expectedVersion());
        audit.record(u,"CATALOG_ROLLBACK",report,"SUCCESS",r.reason());return Result.ok(result);
    }
    @GetMapping("/workbench")
    public Result<?> workbench(@RequestHeader(PermissionService.USER_HEADER) String id,@RequestParam(required=false) String after) {
        return Result.ok(workbench.list(admin(id),after));
    }
    @GetMapping("/workbench/{plan}/items")
    public Result<?> items(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String plan,@RequestParam(defaultValue="1") int page) {
        if(page<1||page>100000) throw new ApiException("页码无效");
        return Result.ok(workbench.items(admin(id),plan,page));
    }
    /**
     * 运维操作原因请求。
     * @param reason 操作原因或状态变更说明；保存前脱敏
     */
    public record Reason(String reason) { }
    @PostMapping("/workbench/{plan}/{action}")
    public Result<?> action(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String plan,@PathVariable String action,@RequestBody Reason reason) {
        return Result.ok(workbench.act(admin(id),plan,action,reason.reason()));
    }
    @PostMapping("/erasures/{conversation}")
    public Result<?> erase(@RequestHeader(PermissionService.USER_HEADER) String id,@PathVariable String conversation,@RequestBody Reason reason) {
        retention.request(permissions.resolve(id),conversation,reason.reason());return Result.ok(Map.of("status","PENDING"));
    }
    @GetMapping("/erasures")
    public Result<?> erasures(@RequestHeader(PermissionService.USER_HEADER) String id) { return Result.ok(retention.pending(admin(id))); }
    @GetMapping("/evaluation")
    public Result<?> evaluate(@RequestHeader(PermissionService.USER_HEADER) String id) {
        var u=admin(id);
        var result=evaluationMonitor.run(u.tenantId());
        audit.record(u,"RESOLVER_EVALUATION","resolver","SUCCESS",null);
        return Result.ok(result);
    }
}
