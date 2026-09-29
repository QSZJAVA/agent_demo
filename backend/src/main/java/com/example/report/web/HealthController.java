package com.example.report.web;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Dependency readiness for deployment probes; no user identity or business data is required. */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final DataSource mysql;
    private final RedisConnectionFactory redis;

    public HealthController(DataSource mysql, RedisConnectionFactory redis) {
        this.mysql = mysql;
        this.redis = redis;
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, String>> readiness() {
        boolean mysqlReady = mysqlReady();
        boolean redisReady = redisReady();
        boolean ready = mysqlReady && redisReady;
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", ready ? "UP" : "DOWN"));
    }

    private boolean mysqlReady() {
        try (Connection connection = mysql.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT 1")) {
            query.setQueryTimeout(1);
            try (ResultSet result = query.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        } catch (SQLException | RuntimeException unavailable) {
            return false;
        }
    }

    private boolean redisReady() {
        try (RedisConnection connection = redis.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
