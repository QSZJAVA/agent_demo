package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 预览快照中的一条记录：执行派单只认快照里的记录
 */
@Data
@TableName("dispatch_preview_item")
public class DispatchPreviewItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String previewId;
    private Integer seq;
    private String reportId;
    private String reportName;
    private Long catalogVersion;
    private String recordId;
    private String docNo;
    private String companyCode;
    private String label;
    private BigDecimal amount;
    private LocalDate bizDate;
    private Long ruleId;
    private String ruleName;
    private Integer ruleVersion;
    private String ruleDescription;
}
