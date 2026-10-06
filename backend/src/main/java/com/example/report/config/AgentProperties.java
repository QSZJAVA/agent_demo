package com.example.report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * agent.* 配置项，当前基线见 demo-baseline.json 与 README
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Llm llm = new Llm();
    private Preview preview = new Preview();
    private Plan plan = new Plan();
    private Resolver resolver = new Resolver();
    private Conversation conversation = new Conversation();
    private Semantic semantic = new Semantic();

    @Data
    public static class Semantic {
        /** 最终演示版仅允许 active，使用 V1 结构化意图。 */
        private String mode = "active";
        private boolean nativeSchema = false;
        private boolean thinkingEnabled = false;
        private String model;
        private int turnTimeoutSeconds = 180;
    }

        @Data
    public static class Llm {
        /** 仅程序回归使用的语义样本开关，正式入口关闭*/
        private boolean mock = false;
    }


        @Data
    public static class Preview {
        private int ttlMinutes = 30;
        /** 预览转分批持久化的阈值，同时限制单份待确认清单条数；更大预览仍可分页浏览*/
        private int maxItems = 5000;
    }

    @Data
    public static class Plan {
        private int ttlMinutes = 10;
    }

    @Data
    public static class Resolver {
        /** 模糊匹配最低得分 */
        private double fuzzyThreshold = 0.6;
        /** 第一名领先不足该分差时视为歧义*/
        private double ambiguityMargin = 0.15;
    }


    @Data
    public static class Conversation {
        private int cardPayloadMaxRows = 2000;
        private int titleMaxLength = 30;
    }
}
