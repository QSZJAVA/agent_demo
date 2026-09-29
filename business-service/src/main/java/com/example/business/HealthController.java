package com.example.business;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    public HealthController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping("/health") public Map<String,String> health(){jdbc.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP","service","report-business-service");}
}
