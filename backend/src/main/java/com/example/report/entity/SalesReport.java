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

    /** 数据所属租户标识；查询和写入必须限定租户。 */
    private String tenantId;

    /** 本表记录主键；数据库自增。*/
    @TableId(type = IdType.AUTO)
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    /** 业务记录所属公司代码；用于公司权限隔离。 */
    private String companyCode;
    /** 销售订单号。*/
    private String orderNo;
    /** 销售产品名称；允许为空，表示尚无该项数据。 */
    private String productName;
    /** 业务金额；小数精度2位，币种沿用来源业务账本。*/
    private BigDecimal amount;
    /** 销售发生日期；允许为空，表示尚无该项数据。 */
    private LocalDate saleDate;
    /** 业务派单状态：0未派单，1已派单。*/
    private Integer dispatchStatus;
    /** 业务派单完成时间；未派单时为空。 */
    private LocalDateTime dispatchedAt;
}
