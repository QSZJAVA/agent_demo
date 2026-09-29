package com.example.report.semantic;

import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Verify the wire contract independently of whether an external gateway honors it. */
class ModelIntentTransportTest {
    @Test void tokenRestorationDoesNotConfuseTheFirstAndTenthEntities() {
        String source=java.util.stream.LongStream.range(0,12).mapToObj(i->"9000000000000000"+String.format("%02d",i)).collect(java.util.stream.Collectors.joining("，"));
        var masked=com.example.report.operations.SensitiveData.modelText(source);
        assertEquals(source,masked.restore(masked.text()));
        assertFalse(masked.text().contains("900000000000000"));
    }
    @Test void malformedOutputGetsOneFormatRepairAndThenFailsClosed() throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var alwaysMalformed=new java.util.concurrent.atomic.AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call=requests.incrementAndGet();
            String content=call==1 || alwaysMalformed.get()?"{":JsonUtil.toJson(new SemanticIntent(2,SemanticIntent.Action.HELP,List.of(),List.of(),SemanticIntent.Clarify.NONE));
            byte[] response=JsonUtil.toJson(Map.of("id","test","object","chat.completion","created",1,"model","test",
                    "choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",content))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var api=OpenAiApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).apiKey("local-test").build();
            var model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder().model("test").build()).build();
            var parser=new ModelIntentParser(model,new IntentCodec(),new AgentProperties());
            assertEquals(SemanticIntent.Action.HELP,parser.parse("帮助",new IntentParser.Context(new DialogueState(),List.of())).action());
            assertEquals(2,requests.get());assertEquals(1,parser.formatRepairs());
            alwaysMalformed.set(true);
            assertThrows(IntentCodec.InvalidOutput.class,()->parser.parse("帮助",new IntentParser.Context(new DialogueState(),List.of())));
            assertEquals(4,requests.get());assertEquals(2,parser.formatRepairs());
        } finally {server.stop(0);}
    }
    @Test void numericDocumentIsTokenizedOnWireAndRestoredBeforeSelection() throws Exception {
        String document="900000000000000001";
        var wire=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);wire.set(body);
            var messages=JsonUtil.MAPPER.readTree(body).get("messages");
            var input=JsonUtil.MAPPER.readTree(messages.get(messages.size()-1).get("content").asText());
            String message=input.get("currentMessage").asText();
            String token=message.substring("排除单据".length());
            String intent=JsonUtil.toJson(new SemanticIntent(2,SemanticIntent.Action.PREVIEW,List.of(
                    new SemanticIntent.ScopeChange(SemanticIntent.Target.RECORDS,SemanticIntent.Operation.ADD,List.of(token),message)),List.of(),SemanticIntent.Clarify.NONE));
            var response=JsonUtil.toJson(Map.of("id","test","object","chat.completion","created",1,"model","test",
                    "choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",intent))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var api=OpenAiApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).apiKey("local-test").build();
            var model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder().model("test").build()).build();
            var state=new DialogueState();
            state.setRecentUserMessages(List.of("电话13812345678"));
            state.setPendingIntent(new SemanticIntent(2,SemanticIntent.Action.PREVIEW,List.of(),List.of(
                    new SemanticIntent.Restriction(SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.RestrictionScope.THIS_TURN,"上轮不要派单")),SemanticIntent.Clarify.NONE));
            var parser=new ModelIntentParser(model,new IntentCodec(),new AgentProperties());
            var intent=parser.parse("排除单据"+document,new IntentParser.Context(state,List.of()));
            assertFalse(wire.get().contains(document));assertFalse(wire.get().contains("13812345678"));
            assertFalse(wire.get().contains("上轮不要派单"));
            assertEquals(document,intent.changesFor(SemanticIntent.Target.RECORDS).get(0).mentions().get(0));
            var row=com.example.report.support.DispatchHarness.candidate("rpt-sales-order","3",document,"A","数字单据");
            assertEquals("3",SelectionResolver.apply(List.of(row),List.of(),intent.changesFor(SemanticIntent.Target.RECORDS).get(0)).get(0).recordId());
        } finally { server.stop(0); }
    }

    @Test void controllerPreservesBusinessInputForLocalEntityProtection() {
        var chat=org.mockito.Mockito.mock(com.example.report.agent.AgentChatService.class);
        var permissions=new com.example.report.permission.PermissionService();
        var controller=new com.example.report.web.AgentController(chat,permissions,null,null);
        var request=new com.example.report.web.AgentController.ChatRequest();
        request.setMessage("排除单据900000000000000001");
        controller.chat("user1",request);
        org.mockito.Mockito.verify(chat).chat(permissions.resolve("user1"),null,request.getMessage(),null,null,null);
    }

    @Test void nativeSchemaIsActuallySentAndToolsAreAbsent() throws Exception {
        var request=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String intent=JsonUtil.toJson(new SemanticIntent(2,SemanticIntent.Action.PREVIEW,SemanticIntent.Change.keep(),SemanticIntent.Change.keep(),SemanticIntent.Change.keep(),SemanticIntent.Clarify.NONE));
        server.createContext("/v1/chat/completions",exchange -> {
            request.set(JsonUtil.MAPPER.readTree(exchange.getRequestBody()));
            var body=JsonUtil.toJson(Map.of("id","test","object","chat.completion","created",1,"model","test",
                    "choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",intent))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var api=OpenAiApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).apiKey("local-test").build();
            var model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder().model("test").build()).build();
            var props=new AgentProperties();props.getSemantic().setNativeSchema(true);
            var parser=new ModelIntentParser(model,new IntentCodec(),props);
            assertEquals(SemanticIntent.Action.PREVIEW,parser.parse("查询",new IntentParser.Context(new DialogueState(),List.of())).action());
            assertEquals("json_schema",request.get().at("/response_format/type").asText());
            assertTrue(request.get().at("/response_format/json_schema/strict").asBoolean());
            assertEquals("PREVIEW",request.get().at("/response_format/json_schema/schema/properties/action/enum/0").asText());
            assertTrue(request.get().path("tools").isMissingNode() || request.get().path("tools").isEmpty());
        } finally {server.stop(0);}
    }
}
