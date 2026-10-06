package com.example.report.agent;

import com.example.report.config.AgentProperties;
import com.example.report.memory.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 会话管理与清理使用的记忆基础设施；V1解析器自行构造有界上下文，不再装配旧工具对话客户端。 */
@Configuration
public class AgentConfig {
    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository repository,AgentProperties props) {
        return MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(props.getMemory().getWindowSize()).build();
    }
}
