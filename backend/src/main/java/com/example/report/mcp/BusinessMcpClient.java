package com.example.report.mcp;

import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Trusted orchestration boundary. Identity is supplied by server code, never by model arguments. */
@Component
@ConditionalOnProperty(name="business.remote.enabled", havingValue="true")
public class BusinessMcpClient {
    private final McpSyncClient client;
    private final ObjectMapper json;
    private final URI healthUri;
    private final java.net.http.HttpClient healthClient=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile boolean initialized;

    public BusinessMcpClient(ObjectMapper json, @Value("${business.remote.url}") String url,
                             @Value("${business.service-token}") String token) {
        URI uri = URI.create(url);
        if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost())))
                || uri.getUserInfo()!=null || token.length()<32) {
            throw new IllegalArgumentException("MCP requires HTTPS (except loopback) and a service token of at least 32 characters");
        }
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.healthUri = uri.resolve("/health");
        var transport = HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(this.json)).connectTimeout(Duration.ofSeconds(3))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .openConnectionOnStartup(false).resumableStreams(false).build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(65)).build();
    }

    private synchronized void initialize() {
        if (!initialized) { client.initialize(); initialized = true; }
    }

    public <T> T call(String tool, CurrentUser user, Map<String,Object> arguments, TypeReference<T> type) {
        Map<String,Object> input = new LinkedHashMap<>(arguments);
        if (user != null) { input.put("operatorId", user.userId()); input.put("tenantId", user.tenantId()); }
        initialize();
        // No automatic retry: a transport failure after a mutation must be reconciled by request ID.
        var result = client.callTool(new McpSchema.CallToolRequest(tool, input));
        if (result == null || result.content() == null) throw new ApiException(502, "MCP 响应不完整");
        try {
            var text = result.content().stream().filter(McpSchema.TextContent.class::isInstance)
                    .map(McpSchema.TextContent.class::cast).findFirst().orElseThrow();
            var envelope = json.readTree(text.text());
            if (Boolean.TRUE.equals(result.isError())) {
                throw new ApiException(envelope.path("code").asInt(502), envelope.path("message").asText("业务工具执行失败"));
            }
            if (!envelope.has("data")) throw new ApiException(502, "MCP 响应缺少业务结果");
            return json.convertValue(envelope.get("data"), type);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(502, "MCP 业务响应格式错误"); }
    }

    @PreDestroy public void close() { client.close(); }

    public boolean available() {
        try {
            var response=healthClient.send(HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(2)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode()==200 && "UP".equals(json.readTree(response.body()).path("status").asText());
        } catch(Exception unavailable) {return false;}
    }
}
