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
        return salesMapper.selectList(new LambdaQueryWrapper<SalesReport>()
                .eq(SalesReport::getTenantId, user.tenantId())
                .in(SalesReport::getCompanyCode, user.companies())
                .orderByAsc(SalesReport::getCompanyCode)
                .orderByAsc(SalesReport::getSaleDate));
    }

    public List<ReceivableReport> listReceivable(CurrentUser user) {
        return receivableMapper.selectList(new LambdaQueryWrapper<ReceivableReport>()
                .eq(ReceivableReport::getTenantId, user.tenantId())
                .in(ReceivableReport::getCompanyCode, user.companies())
                .orderByAsc(ReceivableReport::getCompanyCode)
                .orderByAsc(ReceivableReport::getDueDate));
    }

    public List<ExpenseReport> listExpense(CurrentUser user) {
        return expenseMapper.selectList(new LambdaQueryWrapper<ExpenseReport>()
                .eq(ExpenseReport::getTenantId, user.tenantId())
                .in(ExpenseReport::getCompanyCode, user.companies())
                .orderByAsc(ExpenseReport::getCompanyCode)
                .orderByAsc(ExpenseReport::getExpenseDate));
    }
}
