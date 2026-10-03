package com.example.report;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 报表编排服务启动入口；注册业务控制器、持久化状态和定时恢复任务，业务写入由网关边界执行。
 */
@SpringBootApplication
@EnableScheduling
public class ReportApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportApplication.class, args);
    }
}
