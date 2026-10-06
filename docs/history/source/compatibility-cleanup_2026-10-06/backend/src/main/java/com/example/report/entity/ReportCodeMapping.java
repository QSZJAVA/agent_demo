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

    /** 历史接口报表编码。 */
    @TableId(type = IdType.INPUT)
    private String legacyCode;
    /** 关联 report_definition.report_id 的稳定报表标识。*/
    private String reportId;
    /** 历史编码映射的说明；允许为空，表示尚无该项数据。 */
    private String description;
    /** 记录创建时间。*/
    private LocalDateTime createdAt;
}
