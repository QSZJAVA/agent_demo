package com.example.report.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.entity.ExpenseReport;
import com.example.report.entity.ReceivableReport;
import com.example.report.entity.SalesReport;
import com.example.report.mapper.ExpenseReportMapper;
import com.example.report.mapper.ReceivableReportMapper;
import com.example.report.mapper.SalesReportMapper;
import com.example.report.permission.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 报表查询：按当前用户可见公司过滤（权限在 SQL 里生效）
 */
@Service
public class ReportService {

    private final SalesReportMapper salesMapper;
    private final ReceivableReportMapper receivableMapper;
    private final ExpenseReportMapper expenseMapper;

    public ReportService(SalesReportMapper salesMapper,
                         ReceivableReportMapper receivableMapper,
                         ExpenseReportMapper expenseMapper) {
        this.salesMapper = salesMapper;
        this.receivableMapper = receivableMapper;
        this.expenseMapper = expenseMapper;
    }

    public List<SalesReport> listSales(CurrentUser user) {
        return pageSales(user,1,200).records();
    }

    public ReportPage<SalesReport> pageSales(CurrentUser user,int page,int size) {
        return page(salesMapper,user,new LambdaQueryWrapper<SalesReport>()
                .eq(SalesReport::getTenantId, user.tenantId())
                .in(SalesReport::getCompanyCode, user.companies())
                .orderByAsc(SalesReport::getCompanyCode)
                .orderByAsc(SalesReport::getSaleDate).orderByAsc(SalesReport::getId),page,size);
    }

    public List<ReceivableReport> listReceivable(CurrentUser user) {
        return pageReceivable(user,1,200).records();
    }

    public ReportPage<ReceivableReport> pageReceivable(CurrentUser user,int page,int size) {
        return page(receivableMapper,user,new LambdaQueryWrapper<ReceivableReport>()
                .eq(ReceivableReport::getTenantId, user.tenantId())
                .in(ReceivableReport::getCompanyCode, user.companies())
                .orderByAsc(ReceivableReport::getCompanyCode)
                .orderByAsc(ReceivableReport::getDueDate).orderByAsc(ReceivableReport::getId),page,size);
    }

    public List<ExpenseReport> listExpense(CurrentUser user) {
        return pageExpense(user,1,200).records();
    }

    public ReportPage<ExpenseReport> pageExpense(CurrentUser user,int page,int size) {
        return page(expenseMapper,user,new LambdaQueryWrapper<ExpenseReport>()
                .eq(ExpenseReport::getTenantId, user.tenantId())
                .in(ExpenseReport::getCompanyCode, user.companies())
                .orderByAsc(ExpenseReport::getCompanyCode)
                .orderByAsc(ExpenseReport::getExpenseDate).orderByAsc(ExpenseReport::getId),page,size);
    }

    private <T> ReportPage<T> page(com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper,CurrentUser user,
            LambdaQueryWrapper<T> query,int page,int size) {
        if (page<1 || page>100000 || size<1 || size>200) throw new com.example.report.common.ApiException("页码需为 1–100000，每页条数需为 1–200");
        if (user.companies().isEmpty()) return new ReportPage<>(List.of(),0,page,size);
        long total=mapper.selectCount(query);
        long offset=(page-1L)*size;
        var records=offset>=total ? List.<T>of() : mapper.selectList(query.last("LIMIT "+offset+", "+size));
        return new ReportPage<>(records,total,page,size);
    }
}
