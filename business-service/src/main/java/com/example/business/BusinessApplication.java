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

/** Independent business process. Does not scan Agent, conversations, or mock dispatch components. */
@SpringBootApplication
@EnableScheduling
@Import({MybatisPlusConfig.class,QueryAdapterFactory.class,ReportService.class,RuleEngine.class,IdentityStore.class,DemoDataResetCallback.class})
public class BusinessApplication {
    public static void main(String[] args) { SpringApplication.run(BusinessApplication.class,args); }
}
