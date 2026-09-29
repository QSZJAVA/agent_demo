package com.example.report.semantic;

import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.retry.support.RetryTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in V2 model acceptance. Synthetic data only: no database, no dispatch gateway. */
@EnabledIfEnvironmentVariable(named="SEMANTIC_LIVE_EVAL",matches="true")
class SemanticLiveEvaluationTest {
    @Test void multiTurnAcceptanceReplay() throws Exception {
        var props=new AgentProperties();props.getSemantic().setNativeSchema(Boolean.parseBoolean(System.getenv("SEMANTIC_NATIVE_SCHEMA")));
        props.getSemantic().setThinkingEnabled(Boolean.parseBoolean(System.getenv("SEMANTIC_THINKING_ENABLED")));
        var http=new SimpleClientHttpRequestFactory();http.setConnectTimeout(10000);http.setReadTimeout(45000);
        String model=System.getenv().getOrDefault("LLM_MODEL","deepseek-v4.1-flash");
        var api=OpenAiApi.builder().baseUrl(System.getenv().getOrDefault("LLM_BASE_URL","https://dashscope.aliyuncs.com/compatible-mode"))
                .apiKey(System.getenv("LLM_API_KEY")).completionsPath("/v1/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(http)).build();
        var chat=OpenAiChatModel.builder().openAiApi(api).retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(OpenAiChatOptions.builder().model(model).extraBody(Map.of("thinking",Map.of("type","disabled"))).build()).build();
        var modelParser=new ModelIntentParser(chat,new IntentCodec(),props);
        var parser=new SemanticIntentParser(modelParser);
        String chosen=System.getenv("SEMANTIC_EVAL_CORPUS");
        var corpora=chosen==null || chosen.isBlank()?List.of("replay-corpus.json","business-corpus-v2.json")
                : "all".equals(chosen)?List.of("replay-corpus.json","business-corpus-v2.json","business-additional-corpus.json"):List.of(chosen);
        assertTrue(corpora.stream().allMatch(Set.of("replay-corpus.json","business-corpus-v2.json","business-additional-corpus.json")::contains));
        var evaluation=SemanticEvaluation.run(parser,props,corpora);
        var results=evaluation.results();
        var sources=results.stream().filter(r->r.containsKey("parserSource")).collect(java.util.stream.Collectors.groupingBy(r->r.get("parserSource").toString(),java.util.stream.Collectors.counting()));
        var output=new LinkedHashMap<String,Object>();
        output.putAll(Map.of("protocol",2,"model",model,"nativeSchema",props.getSemantic().isNativeSchema(),"thinkingEnabled",props.getSemantic().isThinkingEnabled(),"corpora",corpora,
                "passed",results.size()-evaluation.failed(),"total",evaluation.expectedTurns(),"parserSources",sources,"cases",results));
        output.put("modelCalls",modelParser.modelCalls());output.put("formatRepairs",modelParser.formatRepairs());
        output.put("promptSha256",com.example.report.common.Digests.sha256(ModelIntentParser.INSTRUCTIONS));
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/semantic-live-evaluation.json"),JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        assertEquals(evaluation.expectedTurns(),results.size(),"Replay incomplete");
        assertEquals(0,evaluation.failed(),"See target/semantic-live-evaluation.json (no business actions executed)");
        assertTrue(results.stream().allMatch(r->"MODEL".equals(r.get("parserSource"))),"No mock or grammar short circuit allowed");
    }
}
