package com.example.business;

import com.example.report.catalog.query.QueryAdapterFactory;
import com.example.report.config.*;
import com.example.report.report.ReportService;
import com.example.report.rule.RuleEngine;
import com.example.report.security.IdentityStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 独立业务服务进程；负责授权来源与快照持久化，只导入共享业务能力，不扫描Agent会话与执行编排组件。 */
@SpringBootApplication
@EnableScheduling
@Import({MybatisPlusConfig.class,QueryAdapterFactory.class,ReportService.class,RuleEngine.class,IdentityStore.class,com.example.report.workspace.QuerySnapshotStore.class})
public class BusinessApplication {
    public static void main(String[] args) { SpringApplication.run(BusinessApplication.class,args); }
}
