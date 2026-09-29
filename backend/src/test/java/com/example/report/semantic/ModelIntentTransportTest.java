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
    @Test void nativeSchemaIsActuallySentAndToolsAreAbsent() throws Exception {
        var request=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String intent=JsonUtil.toJson(new SemanticIntent(1,SemanticIntent.Action.PREVIEW,SemanticIntent.Change.keep(),SemanticIntent.Change.keep(),SemanticIntent.Change.keep(),SemanticIntent.Clarify.NONE));
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
