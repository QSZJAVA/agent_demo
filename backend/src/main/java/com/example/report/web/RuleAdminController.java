package com.example.report.web;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.Result;
import com.example.report.entity.DispatchRule;
import com.example.report.entity.DispatchRuleHistory;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.rule.DispatchCandidateService;
import com.example.report.rule.RuleService;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则管理：查看对有报表权限的用户开放（只能看到自己可访问报表的规则），修改 / 试算 / 发布 / 回滚仅管理员。
 * 规则按 report_id 归属到报表目录。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleAdminController {

    private final RuleService ruleService;
    private final PermissionService permissionService;

    public RuleAdminController(RuleService ruleService, PermissionService permissionService) {
        this.ruleService = ruleService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public Result<List<DispatchRule>> list(@RequestHeader(PermissionService.USER_HEADER) String userId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(ruleService.list(user));
    }

    @GetMapping("/history")
    public Result<List<DispatchRuleHistory>> history(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                     @RequestParam(required = false) String reportId,
                                                     @RequestParam(required = false) String companyCode) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(ruleService.history(user, reportId, companyCode));
    }

    @GetMapping("/fields")
    public Result<List<FieldInfo>> fields(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                          @RequestParam String reportId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(ruleService.fields(user, reportId));
    }

    @PostMapping("/validate")
    public Result<Map<String, Object>> validate(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                @RequestBody ExpressionRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        Set<String> vars = ruleService.validate(user, request.getReportId(), request.getExpression());
        return Result.ok(Map.of("valid", true, "variables", vars));
    }

    @PostMapping("/dry-run")
    public Result<DispatchCandidateService.DryRunResult> dryRun(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                                @RequestBody ExpressionRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        return Result.ok(ruleService.dryRun(user, request.getReportId(), request.getCompanyCode(), request.getExpression()));
    }

    @PostMapping("/drafts")
    public Result<DispatchRule> saveDraft(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                          @RequestBody RuleService.RuleForm form) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        return Result.ok(ruleService.saveDraft(user, form));
    }

    @DeleteMapping("/drafts/{id}")
    public Result<Void> deleteDraft(@RequestHeader(PermissionService.USER_HEADER) String userId, @PathVariable Long id) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        ruleService.deleteDraft(user, id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/publish")
    public Result<DispatchRule> publish(@RequestHeader(PermissionService.USER_HEADER) String userId, @PathVariable Long id) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        return Result.ok(ruleService.publish(user, id));
    }

    @PostMapping("/{id}/disable")
    public Result<DispatchRule> disable(@RequestHeader(PermissionService.USER_HEADER) String userId, @PathVariable Long id) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        return Result.ok(ruleService.disable(user, id));
    }

    @PostMapping("/{id}/rollback")
    public Result<DispatchRule> rollback(@RequestHeader(PermissionService.USER_HEADER) String userId, @PathVariable Long id) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        return Result.ok(ruleService.rollback(user, id));
    }

    /** 规则校验与试算请求；试算只评估事实命中，不改变规则发布状态或执行派单。 */
    @Data
    public static class ExpressionRequest {
        /** 报表目录中的稳定标识 */
        private String reportId;
        /** 试算公司范围；具体公司须有授权，*表示当前用户全部可见公司。 */
        private String companyCode;
        /** Aviator规则表达式；语法和变量必须通过报表事实契约校验。 */
        private String expression;
    }
}
