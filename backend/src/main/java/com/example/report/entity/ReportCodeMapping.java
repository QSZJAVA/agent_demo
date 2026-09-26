package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Demo 时期的 reportType（sales / receivable / expense）到 report_id 的映射，只增不改。
 * 旧规则、旧审计按它回填 report_id；报表页等旧接口仍用旧编码时也经由它找到目录。
 */
@Data
@TableName("report_code_mapping")
public class ReportCodeMapping {

    @TableId(type = IdType.INPUT)
    private String legacyCode;
    private String reportId;
    private String description;
    private LocalDateTime createdAt;
}
