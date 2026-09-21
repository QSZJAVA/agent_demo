package com.example.report.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus：扫描 Mapper 接口；实体与表的映射见 entity 包
 */
@Configuration
@MapperScan("com.example.report.mapper")
public class MybatisPlusConfig {
}
