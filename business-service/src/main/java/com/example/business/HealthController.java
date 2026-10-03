package com.example.business;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
/**
 * 业务服务的数据库存活探测；返回公开健康摘要。此探测不等于通过 MCP 认证或工具契约检查。
 */
@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    public HealthController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping("/health") public Map<String,String> health(){jdbc.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP","service","report-business-service");}
}
