package com.example.report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * agent.* 配置项，与 docs/派单Agent技术选型.md 附录 B 一致
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Llm llm = new Llm();
    private Dispatch dispatch = new Dispatch();
    private Preview preview = new Preview();
    private Plan plan = new Plan();
    private Resolver resolver = new Resolver();
    private Memory memory = new Memory();
    private Conversation conversation = new Conversation();

    @Data
    public static class Llm {
        /** true 时使用关键词模拟模型，不调用真实大模型 */
        private boolean mock = false;
    }

    @Data
    public static class Dispatch {
        /** 派单前是否需要前端确认卡片 */
        private boolean requireConfirm = true;
    }

    @Data
    public static class Preview {
        private int ttlMinutes = 30;
        /** 单次预览记录上限：超出时要求用户缩小范围，不静默截断 */
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
        /** 第一名领先不足该分差时视为歧义 */
        private double ambiguityMargin = 0.15;
    }

    @Data
    public static class Memory {
        private int ttlMinutes = 30;
        private int windowSize = 20;
    }

    @Data
    public static class Conversation {
        private int retentionDays = 365;
        private int cardPayloadMaxRows = 2000;
        private int titleMaxLength = 30;
    }
}
