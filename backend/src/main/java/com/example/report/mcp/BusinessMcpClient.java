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

/**
 * 真实 HTTP MCP 编排边界；用户和租户由服务端注入，模型参数不能覆盖身份。
 * 业务调用不自动重试，网络错误后的写入必须按稳定请求号核对。健康检查使用独立短连接验证数据库、认证与工具契约，不执行派单。
 */
@Component
@ConditionalOnProperty(name="business.remote.enabled", havingValue="true")
public class BusinessMcpClient implements AutoCloseable {
    private final McpSyncClient client;
    private final ObjectMapper json;
    private final URI healthUri;
    private final java.net.http.HttpClient healthClient=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile boolean initialized;
    private final String url;
    private final String token;
    private volatile long probedAt;
    private volatile boolean probeReady;

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessMcpClient(ObjectMapper json, @Value("${business.remote.url}") String url,
                             @Value("${business.service-token}") String token,
                             @Value("${business.remote.allow-insecure-http:false}") boolean allowInternalHttp) {
        URI uri = URI.create(url);
        if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && (allowInternalHttp || java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost()))))
                || uri.getHost()==null || uri.getUserInfo()!=null || token==null || token.length()<32) {
            throw new IllegalArgumentException("MCP requires HTTPS (except loopback) and a service token of at least 32 characters");
        }
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.url=url; this.token=token;
        this.healthUri = uri.resolve("/health");
        var transport = HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(this.json)).connectTimeout(Duration.ofSeconds(3))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .openConnectionOnStartup(false).resumableStreams(false).build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(65)).build();
    }

    public BusinessMcpClient(ObjectMapper json,String url,String token) { this(json,url,token,false); }

    private synchronized void initialize() {
        if (!initialized) { client.initialize(); initialized = true; }
    }

    /**
     * 在受控参数副本中注入服务端用户和租户，初始化协议后调用工具并校验响应信封。
     * @param type 精确业务响应类型，金额按 BigDecimal 解码
     * @throws ApiException MCP 报错、响应缺失或格式不合法；写入异常必须另行核对
     */
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

    /**
     * 缓存10秒的有界可用性检查；验证公开数据库健康和带认证的 MCP 工具列表，检查失败返回false，不触发业务写入。
     */
    public synchronized boolean available() {
        long now=System.nanoTime();
        if(probedAt!=0 && now-probedAt<Duration.ofSeconds(10).toNanos()) return probeReady;
        probeReady=probe();
        probedAt=System.nanoTime();
        return probeReady;
    }

    private boolean probe() {
        try {
            var response=healthClient.send(HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(2)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || !"UP".equals(json.readTree(response.body()).path("status").asText())) return false;
            // Separate, bounded protocol connection: never waits behind a mutation or resends one.
            var transport=HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp")
                    .jsonMapper(new JacksonMcpJsonMapper(json)).connectTimeout(Duration.ofSeconds(2))
                    .requestBuilder(HttpRequest.newBuilder().header("Authorization","Bearer "+token))
                    .openConnectionOnStartup(false).resumableStreams(false).build();
            try(var probe=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(2)).build()) {
                probe.initialize();
                var names=probe.listTools().tools().stream().map(McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet());
                return names.containsAll(java.util.Set.of("report_catalog","report_page","report_records","dispatch_submit","dispatch_lookup","report_probe"));
            }
        } catch(Exception unavailable) {return false;}
    }
}
