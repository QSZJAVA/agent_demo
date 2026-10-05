package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.springframework.ai.chat.messages.UserMessage;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 本机HTTP响应验证当前Spring AI工具与报告传输；测试响应不作为真实供应商能力证据。 */
class InvestigationTransportTest {
    @Test void sendsOnlyReadToolsAndPreservesUsageWithoutAutomaticExecution() throws Exception {
        var wire=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            var body=JsonUtil.MAPPER.readTree(exchange.getRequestBody());wire.set(body);
            Map<String,Object> message=body.has("tools") && body.get("tools").size()>0?Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","call1","type","function","function",Map.of("name","investigation_plan_summary","arguments","{}")))):Map.of("role","assistant","content","{}");
            byte[] output=JsonUtil.toJson(Map.of("id","test","object","chat.completion","created",1,"model","test","choices",List.of(Map.of("index",0,"finish_reason","stop","message",message)),"usage",Map.of("prompt_tokens",120,"completion_tokens",30,"total_tokens",150))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);exchange.close();
        });server.start();
        try {
            var model=new InvestigationOpenAiModel(new InvestigationProperties(),"http://127.0.0.1:"+server.getAddress().getPort(),"local-test","test","/v1/chat/completions");
            var result=model.call(List.of(new UserMessage("查询当前绑定的范围")),InvestigationTools.definitions(),false,Duration.ofSeconds(5));
            assertEquals(5,wire.get().get("tools").size());assertEquals("call1",result.message().getToolCalls().get(0).id());assertEquals(120,result.usage().get("inputTokens"));assertTrue((Boolean)result.usage().get("usageComplete"));
            assertFalse(wire.get().toString().contains("tenantId"));assertFalse(wire.get().toString().contains("dispatch_submit"));
            model.call(List.of(new UserMessage("输出报告")),List.of(),true,Duration.ofSeconds(5));assertTrue(!wire.get().has("tools") || wire.get().get("tools").isEmpty());
        } finally {server.stop(0);}
    }
    @Test void actualHttpReadIsBoundedAndInterruptedCallReleasesCaller() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=java.util.concurrent.Executors.newCachedThreadPool();server.setExecutor(executor);
        var entered=new java.util.concurrent.CountDownLatch(2);
        server.createContext("/v1/chat/completions",exchange -> {exchange.getRequestBody().readAllBytes();entered.countDown();try {Thread.sleep(4000);} catch(InterruptedException ignored) {Thread.currentThread().interrupt();} finally {exchange.close();}});server.start();
        try {
            var model=new InvestigationOpenAiModel(new InvestigationProperties(),"http://127.0.0.1:"+server.getAddress().getPort(),"local-test","test","/v1/chat/completions");long started=System.nanoTime();
            assertEquals("MODEL_UNAVAILABLE",assertThrows(InvestigationFailure.class,() -> model.call(List.of(new UserMessage("超时")),List.of(),false,Duration.ofMillis(200))).reason());
            assertTrue(System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
            var completed=new java.util.concurrent.CountDownLatch(1);var caller=new Thread(() -> {try {model.call(List.of(new UserMessage("中断")),List.of(),false,Duration.ofSeconds(10));} catch(InvestigationFailure ignored) {} finally {completed.countDown();}});
            caller.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));caller.interrupt();assertTrue(completed.await(2,java.util.concurrent.TimeUnit.SECONDS));caller.join(1000);
        } finally {server.stop(0);executor.shutdownNow();}
    }
    @Test void mcpInitializationTimeoutCannotHangOrAcceptWriteTool() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=java.util.concurrent.Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/mcp",exchange -> {exchange.getRequestBody().readAllBytes();try {Thread.sleep(4000);} catch(InterruptedException ignored) {Thread.currentThread().interrupt();} finally {exchange.close();}});server.start();
        try(var client=new com.example.report.mcp.BusinessMcpClient(JsonUtil.MAPPER,"http://127.0.0.1:"+server.getAddress().getPort(),UUID.randomUUID().toString())) {
            var type=new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){};long started=System.nanoTime();
            var error=assertThrows(com.example.report.common.ApiException.class,() -> client.readOnlyCall("dispatch_lookup",InvestigationTestSupport.ACTOR,Map.of("requestId","bound-request"),type,Duration.ofMillis(400)));
            assertEquals(503,error.getCode());assertTrue(System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
            assertThrows(IllegalArgumentException.class,() -> client.readOnlyCall("dispatch_submit",InvestigationTestSupport.ACTOR,Map.of(),type,Duration.ofSeconds(1)));
        } finally {server.stop(0);executor.shutdownNow();}
    }
    @Test void mcpHttpAuthenticationRejectionIsNotFoldedIntoUnknownResult() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",exchange -> {exchange.getRequestBody().readAllBytes();byte[] output="{\"code\":401,\"message\":\"authentication required\"}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(401,output.length);exchange.getResponseBody().write(output);exchange.close();});server.start();
        try(var client=new com.example.report.mcp.BusinessMcpClient(JsonUtil.MAPPER,"http://127.0.0.1:"+server.getAddress().getPort(),UUID.randomUUID().toString())) {
            var error=assertThrows(com.example.report.common.ApiException.class,() -> client.readOnlyCall("dispatch_lookup",InvestigationTestSupport.ACTOR,Map.of("requestId","bound-request"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){},Duration.ofSeconds(2)));
            assertEquals(401,error.getCode());
        } finally {server.stop(0);}
    }
}
