package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 应收报表（来源表：report_receivable）
 */
@Data
@TableName("report_receivable")
public class ReceivableReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String companyCode;
    private String invoiceNo;
    private String customerName;
    private BigDecimal amount;
    private LocalDate dueDate;
    /** 0 未派单 1 已派单 */
    private Integer dispatchStatus;
    private LocalDateTime dispatchedAt;
}
