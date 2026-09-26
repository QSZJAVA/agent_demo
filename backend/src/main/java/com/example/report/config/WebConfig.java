package com.example.report.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 跨域 + SSE 异步写线程池
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 允许跨域的来源；为空时不注册 CORS，只接受同源请求 */
    private final List<String> corsAllowedOrigins;

    public WebConfig(@Value("${web.cors-allowed-origins:}") List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 身份只靠请求头，任意来源 + 允许凭据等于把接口开放给所有网页，所以只放行显式配置的来源
        if (corsAllowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(corsAllowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean(name = "mvcAsyncExecutor")
    public ThreadPoolTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sse-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Spring MVC 把 Flux<ServerSentEvent> 转成 SSE 需要一个写线程池
        configurer.setTaskExecutor(mvcAsyncExecutor());
        configurer.setDefaultTimeout(300_000);
    }
}
