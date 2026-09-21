package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.entity.DispatchRule;
import com.example.report.entity.DispatchRuleHistory;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.rule.DispatchCandidateService;
import com.example.report.rule.RuleService;
import com.example.report.rule.fact.FieldInfo;
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
 * 规则管理：查看对所有用户开放，修改 / 试算 / 发布 / 回滚仅管理员
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
        permissionService.resolve(userId);
        return Result.ok(ruleService.list());
    }

    @GetMapping("/history")
    public Result<List<DispatchRuleHistory>> history(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                     @RequestParam(required = false) String reportType,
                                                     @RequestParam(required = false) String companyCode) {
        permissionService.resolve(userId);
        return Result.ok(ruleService.history(reportType, companyCode));
    }

    @GetMapping("/fields")
    public Result<List<FieldInfo>> fields(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                          @RequestParam String reportType) {
        permissionService.resolve(userId);
        return Result.ok(ruleService.fields(reportType));
    }

    @PostMapping("/validate")
    public Result<Map<String, Object>> validate(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                @RequestBody ExpressionRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        Set<String> vars = ruleService.validate(request.getReportType(), request.getExpression());
        return Result.ok(Map.of("valid", true, "variables", vars));
    }

    @PostMapping("/dry-run")
    public Result<DispatchCandidateService.DryRunResult> dryRun(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                                @RequestBody ExpressionRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        permissionService.requireAdmin(user);
        return Result.ok(ruleService.dryRun(user, request.getReportType(), request.getCompanyCode(), request.getExpression()));
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
        ruleService.deleteDraft(id);
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

    @Data
    public static class ExpressionRequest {
        private String reportType;
        private String companyCode;
        private String expression;
    }
}
