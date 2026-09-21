package com.example.report.report;

import com.example.report.common.ApiException;

import java.util.Arrays;

/**
 * 三张报表的枚举：code 用于接口与规则表，label 用于展示
 */
public enum ReportType {

    SALES("sales", "销售报表", "订单号"),
    RECEIVABLE("receivable", "应收报表", "发票号"),
    EXPENSE("expense", "费用报表", "报销单号");

    private final String code;
    private final String label;
    private final String docNoLabel;

    ReportType(String code, String label, String docNoLabel) {
        this.code = code;
        this.label = label;
        this.docNoLabel = docNoLabel;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String docNoLabel() {
        return docNoLabel;
    }

    public static ReportType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ApiException("报表类型不能为空");
        }
        String c = code.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(t -> t.code.equals(c))
                .findFirst()
                .orElseThrow(() -> new ApiException("未知报表类型：" + code + "，可选 sales / receivable / expense"));
    }

    /** 允许为空的解析：空串或 null 返回 null，表示"全部报表" */
    public static ReportType fromCodeOrNull(String code) {
        if (code == null || code.isBlank() || "all".equalsIgnoreCase(code.trim())) {
            return null;
        }
        return fromCode(code);
    }
}
