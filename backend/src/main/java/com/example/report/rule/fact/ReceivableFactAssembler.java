package com.example.report.rule.fact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.entity.ReceivableReport;
import com.example.report.mapper.ReceivableReportMapper;
import com.example.report.report.ReportType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 应收报表事实模型
 */
@Component
public class ReceivableFactAssembler implements FactAssembler {

    private static final List<FieldInfo> FIELDS = List.of(
            new FieldInfo("companyCode", "string", "公司代码"),
            new FieldInfo("invoiceNo", "string", "发票号"),
            new FieldInfo("customerName", "string", "客户名称"),
            new FieldInfo("amount", "decimal", "应收金额（元）"),
            new FieldInfo("dueDate", "date", "到期日，字符串 yyyy-MM-dd"),
            new FieldInfo("daysUntilDue", "long", "距到期日的天数，已过期为负数（派生）"),
            new FieldInfo("overdue", "boolean", "是否已逾期（派生）"),
            new FieldInfo("dispatched", "boolean", "是否已派单（派生，粗筛后恒为 false）")
    );

    private final ReceivableReportMapper mapper;

    public ReceivableFactAssembler(ReceivableReportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReportType type() {
        return ReportType.RECEIVABLE;
    }

    @Override
    public List<FieldInfo> fields() {
        return FIELDS;
    }

    @Override
    public List<FactRow> rows(Set<String> companies) {
        if (companies.isEmpty()) {
            return List.of();
        }
        List<ReceivableReport> list = mapper.selectList(new LambdaQueryWrapper<ReceivableReport>()
                .in(ReceivableReport::getCompanyCode, companies)
                .eq(ReceivableReport::getDispatchStatus, 0)
                .orderByAsc(ReceivableReport::getCompanyCode)
                .orderByAsc(ReceivableReport::getDueDate));
        LocalDate today = LocalDate.now();
        return list.stream().map(r -> {
            long daysUntilDue = r.getDueDate() == null ? 0L : ChronoUnit.DAYS.between(today, r.getDueDate());
            Map<String, Object> facts = new HashMap<>();
            facts.put("companyCode", r.getCompanyCode());
            facts.put("invoiceNo", r.getInvoiceNo());
            facts.put("customerName", r.getCustomerName());
            facts.put("amount", r.getAmount());
            facts.put("dueDate", r.getDueDate() == null ? null : r.getDueDate().toString());
            facts.put("daysUntilDue", daysUntilDue);
            facts.put("overdue", r.getDueDate() != null && daysUntilDue < 0);
            facts.put("dispatched", r.getDispatchStatus() != null && r.getDispatchStatus() == 1);
            return new FactRow(r.getId(), r.getInvoiceNo(), r.getCompanyCode(), r.getCustomerName(), r.getAmount(), r.getDueDate(), facts);
        }).toList();
    }
}
