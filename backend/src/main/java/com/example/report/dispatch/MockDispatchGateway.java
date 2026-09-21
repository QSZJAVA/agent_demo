package com.example.report.dispatch;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.report.entity.ExpenseReport;
import com.example.report.entity.ReceivableReport;
import com.example.report.entity.SalesReport;
import com.example.report.mapper.ExpenseReportMapper;
import com.example.report.mapper.ReceivableReportMapper;
import com.example.report.mapper.SalesReportMapper;
import com.example.report.report.ReportType;
import com.example.report.rule.Candidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 派单接口的模拟实现：把记录标记为已派单。真实系统替换为调用现有派单接口。
 */
@Slf4j
@Component
public class MockDispatchGateway implements DispatchGateway {

    private final SalesReportMapper salesMapper;
    private final ReceivableReportMapper receivableMapper;
    private final ExpenseReportMapper expenseMapper;

    public MockDispatchGateway(SalesReportMapper salesMapper, ReceivableReportMapper receivableMapper, ExpenseReportMapper expenseMapper) {
        this.salesMapper = salesMapper;
        this.receivableMapper = receivableMapper;
        this.expenseMapper = expenseMapper;
    }

    @Override
    public Outcome dispatch(Candidate c) {
        LocalDateTime now = LocalDateTime.now();
        int affected = switch (ReportType.fromCode(c.reportType())) {
            case SALES -> salesMapper.update(null, new LambdaUpdateWrapper<SalesReport>()
                    .eq(SalesReport::getId, c.recordId()).eq(SalesReport::getDispatchStatus, 0)
                    .set(SalesReport::getDispatchStatus, 1).set(SalesReport::getDispatchedAt, now));
            case RECEIVABLE -> receivableMapper.update(null, new LambdaUpdateWrapper<ReceivableReport>()
                    .eq(ReceivableReport::getId, c.recordId()).eq(ReceivableReport::getDispatchStatus, 0)
                    .set(ReceivableReport::getDispatchStatus, 1).set(ReceivableReport::getDispatchedAt, now));
            case EXPENSE -> expenseMapper.update(null, new LambdaUpdateWrapper<ExpenseReport>()
                    .eq(ExpenseReport::getId, c.recordId()).eq(ExpenseReport::getDispatchStatus, 0)
                    .set(ExpenseReport::getDispatchStatus, 1).set(ExpenseReport::getDispatchedAt, now));
        };
        if (affected == 0) {
            return Outcome.fail("记录不存在或已派单");
        }
        log.info("[模拟派单接口] {} {} {} 金额 {} 派单成功", c.reportName(), c.companyCode(), c.docNo(), c.amount());
        return Outcome.ok();
    }
}
