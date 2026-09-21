package com.example.report.rule.fact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.entity.ExpenseReport;
import com.example.report.mapper.ExpenseReportMapper;
import com.example.report.report.ReportType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 费用报表事实模型
 */
@Component
public class ExpenseFactAssembler implements FactAssembler {

    private static final List<FieldInfo> FIELDS = List.of(
            new FieldInfo("companyCode", "string", "公司代码"),
            new FieldInfo("expenseNo", "string", "报销单号"),
            new FieldInfo("expenseType", "string", "费用类型，如 差旅费 / 业务招待费 / 办公用品"),
            new FieldInfo("amount", "decimal", "报销金额（元）"),
            new FieldInfo("expenseDate", "date", "发生日期，字符串 yyyy-MM-dd"),
            new FieldInfo("daysSinceExpense", "long", "距发生日期的天数（派生）"),
            new FieldInfo("dispatched", "boolean", "是否已派单（派生，粗筛后恒为 false）")
    );

    private final ExpenseReportMapper mapper;

    public ExpenseFactAssembler(ExpenseReportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReportType type() {
        return ReportType.EXPENSE;
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
        List<ExpenseReport> list = mapper.selectList(new LambdaQueryWrapper<ExpenseReport>()
                .in(ExpenseReport::getCompanyCode, companies)
                .eq(ExpenseReport::getDispatchStatus, 0)
                .orderByAsc(ExpenseReport::getCompanyCode)
                .orderByAsc(ExpenseReport::getExpenseDate));
        LocalDate today = LocalDate.now();
        return list.stream().map(r -> {
            Map<String, Object> facts = new HashMap<>();
            facts.put("companyCode", r.getCompanyCode());
            facts.put("expenseNo", r.getExpenseNo());
            facts.put("expenseType", r.getExpenseType());
            facts.put("amount", r.getAmount());
            facts.put("expenseDate", r.getExpenseDate() == null ? null : r.getExpenseDate().toString());
            facts.put("daysSinceExpense", r.getExpenseDate() == null ? 0L : ChronoUnit.DAYS.between(r.getExpenseDate(), today));
            facts.put("dispatched", r.getDispatchStatus() != null && r.getDispatchStatus() == 1);
            return new FactRow(r.getId(), r.getExpenseNo(), r.getCompanyCode(), r.getExpenseType(), r.getAmount(), r.getExpenseDate(), facts);
        }).toList();
    }
}
