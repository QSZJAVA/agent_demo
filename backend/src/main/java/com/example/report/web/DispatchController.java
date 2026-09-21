package com.example.report.web;

import com.example.report.common.ApiException;
import com.example.report.common.Result;
import com.example.report.dispatch.DispatchResultPayload;
import com.example.report.dispatch.DispatchService;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.report.ReportType;
import lombok.Data;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 派单执行：确认卡片 → 执行清单；报表页 → 手工派单
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final DispatchService dispatchService;
    private final PermissionService permissionService;

    public DispatchController(DispatchService dispatchService, PermissionService permissionService) {
        this.dispatchService = dispatchService;
        this.permissionService = permissionService;
    }

    /** 确认执行待确认清单（不经过模型，最终门槛在这里） */
    @PostMapping("/plans/{planId}/execute")
    public Result<DispatchResultPayload> execute(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                 @PathVariable String planId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(dispatchService.executePlan(user, planId));
    }

    @PostMapping("/plans/{planId}/cancel")
    public Result<Void> cancel(@RequestHeader(PermissionService.USER_HEADER) String userId, @PathVariable String planId) {
        CurrentUser user = permissionService.resolve(userId);
        dispatchService.cancelPlan(user, planId);
        return Result.ok(null);
    }

    /** 报表页手工派单：按记录 ID */
    @PostMapping("/direct")
    public Result<DispatchResultPayload> direct(@RequestHeader(PermissionService.USER_HEADER) String userId,
                                                @RequestBody DirectRequest request) {
        CurrentUser user = permissionService.resolve(userId);
        if (request.getIds() == null || request.getIds().isEmpty()) {
            throw new ApiException("请先勾选需要派单的记录");
        }
        return Result.ok(dispatchService.dispatchDirect(user, ReportType.fromCode(request.getReportType()), request.getIds()));
    }

    @Data
    public static class DirectRequest {
        private String reportType;
        private List<Long> ids;
    }
}
