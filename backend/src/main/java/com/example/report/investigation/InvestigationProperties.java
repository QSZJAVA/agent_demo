package com.example.report.investigation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 调查专用预算与队列配置；沿用当前技术栈，限制实际模型、工具、远端读取及持久化体积。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.investigation")
public class InvestigationProperties {
    private int maxItems = 10;
    private int maxModelCalls = 8;
    private int maxCollectionCalls = 6;
    private int maxToolCalls = 16;
    private int maxMcpCalls = 20;
    private int maxOutputTokens = 2400;
    private int maxReportOutputTokens = 4096;
    private int maxInputUtf8Bytes = 98304;
    /** 收集阶段超过此UTF-8字节数时整理笔记；不是硬截断阈值，实际HTTP上限仍独立生效。 */
    private int contextTargetUtf8Bytes = 24576;
    private int maxToolResultUtf8Bytes = 8192;
    private int runTimeoutSeconds = 180;
    private int modelTimeoutSeconds = 40;
    private int mcpTimeoutSeconds = 8;
    private int queueTimeoutSeconds = 120;
    private int workerCount = 2;
    private int maxActivePerUser = 3;
    private int maxActivePerTenant = 12;
    private int leaseSeconds = 45;
    private int heartbeatSeconds = 10;
    private int retentionDays = 7;
    private String model;
    private boolean nativeSchema;
    private boolean thinkingEnabled;
    private String businessTimezone="Asia/Shanghai";
    private java.math.BigDecimal inputPricePerMillion;
    private java.math.BigDecimal cachedInputPricePerMillion;
    private java.math.BigDecimal outputPricePerMillion;
    private String currency;

    /** 启动时拒绝无效预算，防止无限循环或续租周期超过租约；不自动放大端点能力。 */
    public void validate() {
        java.time.ZoneId.of(businessTimezone);
        for(var price:new java.math.BigDecimal[]{inputPricePerMillion,cachedInputPricePerMillion,outputPricePerMillion})
            if(price!=null && price.signum()<0) throw new IllegalArgumentException("调查计费价格不能为负数");
        if(currency!=null && !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("调查计费币种须使用三位大写代码");
        if (maxItems < 1 || maxItems > 10 || maxCollectionCalls < 1 || maxModelCalls < maxCollectionCalls + 2
                || maxToolCalls < 1 || maxMcpCalls < 1 || maxOutputTokens < 1 || maxReportOutputTokens < 1
                || maxInputUtf8Bytes < 1024 || contextTargetUtf8Bytes < 1024 || maxToolResultUtf8Bytes < 512 || runTimeoutSeconds < 1
                || modelTimeoutSeconds < 1 || mcpTimeoutSeconds < 1 || queueTimeoutSeconds < 1
                || workerCount < 1 || workerCount > 4 || maxActivePerUser < 1 || maxActivePerTenant < maxActivePerUser
                || heartbeatSeconds < 1 || leaseSeconds < heartbeatSeconds * 3 || retentionDays < 1)
            throw new IllegalArgumentException("调查预算或租约配置无效");
    }
}
