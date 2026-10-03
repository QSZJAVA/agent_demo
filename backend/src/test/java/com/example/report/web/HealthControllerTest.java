package com.example.report.web;

import com.example.report.common.GlobalExceptionHandler;
import com.example.report.config.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {
    private final DataSource mysql = mock(DataSource.class);
    private final Connection mysqlConnection = mock(Connection.class);
    private final PreparedStatement query = mock(PreparedStatement.class);
    private final ResultSet result = mock(ResultSet.class);
    private final RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    private final RedisConnection connection = mock(RedisConnection.class);
    private MockMvc http;
    private HealthController controller;

    @BeforeEach
    void setUp() throws Exception {
        when(mysql.getConnection()).thenReturn(mysqlConnection);
        when(mysqlConnection.prepareStatement("SELECT 1")).thenReturn(query);
        when(query.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
        when(redis.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        controller=new HealthController(mysql,redis);
        http = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter()).build();
    }

    @Test
    void healthyDependenciesReturnHttp200WithoutUserIdentity() throws Exception {
        http.perform(get("/api/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
        verify(mysqlConnection).prepareStatement("SELECT 1");
        verify(query).setQueryTimeout(1);
        verify(mysqlConnection).close();
        verify(query).close();
        verify(result).close();
        verify(connection).ping();
        verify(connection).close();
    }

    @Test
    void mysqlFailureReturnsHttp503AndDoesNotExposeConnectionDetails() throws Exception {
        when(mysql.getConnection()).thenThrow(
                new SQLException("jdbc:mysql://private-host/db?password=secret"));
        expectDown();
        verify(connection).ping();
        verify(connection).close();
    }

    @Test
    void redisPingFailureReturnsHttp503AndClosesItsConnection() throws Exception {
        when(connection.ping()).thenThrow(new IllegalStateException("redis://user:secret@private-host"));
        expectDown();
        verify(connection).close();
    }

    @Test
    void redisConnectionFailureReturnsHttp503InsteadOfBusinessHttp200() throws Exception {
        when(redis.getConnection()).thenThrow(new IllegalStateException("private Redis connection failure"));
        expectDown();
    }

    @Test
    void unexpectedDependencyRepliesAreNotReportedAsHealthy() throws Exception {
        when(result.next()).thenReturn(false);
        expectDown();
        when(result.next()).thenReturn(true);
        when(connection.ping()).thenReturn(null);
        expectDown();
    }

    @Test
    void bothFailuresStillReturnOnlyTheReadinessState() throws Exception {
        when(mysql.getConnection()).thenThrow(new SQLException("mysql details"));
        when(redis.getConnection()).thenThrow(new IllegalStateException("redis details"));
        expectDown();
        verify(redis).getConnection();
    }

    @Test
    void mysqlQueryTimeoutReturnsHttp503AndClosesResources() throws Exception {
        when(query.executeQuery()).thenThrow(new java.sql.SQLTimeoutException("private query timeout details"));
        expectDown();
        verify(query).setQueryTimeout(1);
        verify(query).close();
        verify(mysqlConnection).close();
    }

    @Test void unavailableAuthenticatedMcpMarksReadinessDown() throws Exception {
        var business=mock(com.example.report.mcp.BusinessMcpClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"business",business);
        when(business.available()).thenReturn(false);expectDown();
        when(business.available()).thenReturn(true);
        http.perform(get("/api/health/readiness")).andExpect(status().isOk());
    }

    private void expectDown() throws Exception {
        http.perform(get("/api/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", true));
    }
}
