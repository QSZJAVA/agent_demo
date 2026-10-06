package com.example.report.web;

import com.example.report.catalog.ReportCatalogService;
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
import org.springframework.web.bind.annotation.RequestParam;
import com.example.report.report.ReportPage;

import java.util.List;

/**
 * 三张报表的查询页接口：按稳定报表标识校验目录权限，再按可见公司过滤
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final ReportService reportService;
    private final PermissionService permissionService;
    private final ReportCatalogService catalogService;

    public ReportController(ReportService reportService, PermissionService permissionService, ReportCatalogService catalogService) {
        this.reportService = reportService;
        this.permissionService = permissionService;
        this.catalogService = catalogService;
    }

    /** 报表一：销售报表 */
    @GetMapping("/sales/page")
    public Result<ReportPage<SalesReport>> salesPage(@RequestHeader(PermissionService.USER_HEADER) String userId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size) {
        CurrentUser user=permissionService.resolve(userId);
        catalogService.requireVisible(user,"rpt-sales-order");
        return Result.ok(reportService.pageSales(user,page,size));
    }

    @GetMapping("/receivable/page")
    public Result<ReportPage<ReceivableReport>> receivablePage(@RequestHeader(PermissionService.USER_HEADER) String userId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size) {
        CurrentUser user=permissionService.resolve(userId);
        catalogService.requireVisible(user,"rpt-ar-invoice");
        return Result.ok(reportService.pageReceivable(user,page,size));
    }

    @GetMapping("/expense/page")
    public Result<ReportPage<ExpenseReport>> expensePage(@RequestHeader(PermissionService.USER_HEADER) String userId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size) {
        CurrentUser user=permissionService.resolve(userId);
        catalogService.requireVisible(user,"rpt-expense-claim");
        return Result.ok(reportService.pageExpense(user,page,size));
    }

    @GetMapping("/sales")
    public Result<List<SalesReport>> sales(@RequestHeader(PermissionService.USER_HEADER) String userId) {
        CurrentUser user = permissionService.resolve(userId);
        catalogService.requireVisible(user, "rpt-sales-order");
        return Result.ok(reportService.listSales(user));
    }

    /** 报表二：应收报表*/
    @GetMapping("/receivable")
    public Result<List<ReceivableReport>> receivable(@RequestHeader(PermissionService.USER_HEADER) String userId) {
        CurrentUser user = permissionService.resolve(userId);
        catalogService.requireVisible(user, "rpt-ar-invoice");
        return Result.ok(reportService.listReceivable(user));
    }

    /** 报表三：费用报表 */
    @GetMapping("/expense")
    public Result<List<ExpenseReport>> expense(@RequestHeader(PermissionService.USER_HEADER) String userId) {
        CurrentUser user = permissionService.resolve(userId);
        catalogService.requireVisible(user, "rpt-expense-claim");
        return Result.ok(reportService.listExpense(user));
    }
}
