package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售报表（来源表：report_sales）
 */
@Data
@TableName("report_sales")
public class SalesReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String companyCode;
    private String orderNo;
    private String productName;
    private BigDecimal amount;
    private LocalDate saleDate;
    /** 0 未派单 1 已派单 */
    private Integer dispatchStatus;
    private LocalDateTime dispatchedAt;
}
