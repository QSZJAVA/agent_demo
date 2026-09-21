package com.example.report.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 对话日志异步写入线程池：单线程，保证同一请求内各条记录按发生顺序落库
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String CONVERSATION_LOG_EXECUTOR = "conversationLogExecutor";

    @Bean(name = CONVERSATION_LOG_EXECUTOR)
    public ThreadPoolTaskExecutor conversationLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("conv-log-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10_000);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
