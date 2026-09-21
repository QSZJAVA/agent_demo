package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 费用报表（来源表：report_expense）
 */
@Data
@TableName("report_expense")
public class ExpenseReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String companyCode;
    private String expenseNo;
    private String expenseType;
    private BigDecimal amount;
    private LocalDate expenseDate;
    /** 0 未派单 1 已派单 */
    private Integer dispatchStatus;
    private LocalDateTime dispatchedAt;
}
