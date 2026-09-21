package com.example.report.rule.fact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.entity.SalesReport;
import com.example.report.mapper.SalesReportMapper;
import com.example.report.report.ReportType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 销售报表事实模型
 */
@Component
public class SalesFactAssembler implements FactAssembler {

    private static final List<FieldInfo> FIELDS = List.of(
            new FieldInfo("companyCode", "string", "公司代码"),
            new FieldInfo("orderNo", "string", "订单号"),
            new FieldInfo("productName", "string", "产品名称"),
            new FieldInfo("amount", "decimal", "订单金额（元）"),
            new FieldInfo("saleDate", "date", "销售日期，字符串 yyyy-MM-dd"),
            new FieldInfo("daysSinceSale", "long", "距销售日期的天数（派生）"),
            new FieldInfo("dispatched", "boolean", "是否已派单（派生，粗筛后恒为 false）")
    );

    private final SalesReportMapper mapper;

    public SalesFactAssembler(SalesReportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReportType type() {
        return ReportType.SALES;
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
        List<SalesReport> list = mapper.selectList(new LambdaQueryWrapper<SalesReport>()
                .in(SalesReport::getCompanyCode, companies)
                .eq(SalesReport::getDispatchStatus, 0)
                .orderByAsc(SalesReport::getCompanyCode)
                .orderByAsc(SalesReport::getSaleDate));
        LocalDate today = LocalDate.now();
        return list.stream().map(r -> {
            Map<String, Object> facts = new HashMap<>();
            facts.put("companyCode", r.getCompanyCode());
            facts.put("orderNo", r.getOrderNo());
            facts.put("productName", r.getProductName());
            facts.put("amount", r.getAmount());
            facts.put("saleDate", r.getSaleDate() == null ? null : r.getSaleDate().toString());
            facts.put("daysSinceSale", r.getSaleDate() == null ? 0L : ChronoUnit.DAYS.between(r.getSaleDate(), today));
            facts.put("dispatched", r.getDispatchStatus() != null && r.getDispatchStatus() == 1);
            return new FactRow(r.getId(), r.getOrderNo(), r.getCompanyCode(), r.getProductName(), r.getAmount(), r.getSaleDate(), facts);
        }).toList();
    }
}
