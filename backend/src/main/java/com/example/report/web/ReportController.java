package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.entity.ExpenseReport;
import com.example.report.entity.ReceivableReport;
import com.example.report.entity.SalesReport;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.report.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 三张报表查询接口（接口路径不变，新增按用户可见公司过滤）
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final ReportService reportService;
    private final PermissionService permissionService;

    public ReportController(ReportService reportService, PermissionService permissionService) {
        this.reportService = reportService;
        this.permissionService = permissionService;
    }

    /** 报表一：销售报表 */
    @GetMapping("/sales")
    public Result<List<SalesReport>> sales(@RequestHeader(PermissionService.USER_HEADER) String userId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(reportService.listSales(user));
    }

    /** 报表二：应收报表 */
    @GetMapping("/receivable")
    public Result<List<ReceivableReport>> receivable(@RequestHeader(PermissionService.USER_HEADER) String userId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(reportService.listReceivable(user));
    }

    /** 报表三：费用报表 */
    @GetMapping("/expense")
    public Result<List<ExpenseReport>> expense(@RequestHeader(PermissionService.USER_HEADER) String userId) {
        CurrentUser user = permissionService.resolve(userId);
        return Result.ok(reportService.listExpense(user));
    }
}
