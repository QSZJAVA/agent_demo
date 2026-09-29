package com.example.report.semantic;

import com.example.report.catalog.*;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.support.TestCatalog;
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

/** Opt-in acceptance replay. Records DOMAIN/MODEL/MOCK separately; no DB or dispatch service. */
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
        IntentParser parser="model".equals(System.getenv("SEMANTIC_EVAL_PARSER"))?modelParser:new SemanticIntentParser(modelParser);
        var catalog=new ReportCatalogService(new TestCatalog().catalog(),props);
        var planner=new SemanticPlanner(catalog);
        List<Map<String,Object>> results=new ArrayList<>();
        try(var input=getClass().getResourceAsStream("/semantic/replay-corpus.json")) {
            var scenarios=JsonUtil.MAPPER.readTree(input);
            evaluation: for(var scenario:scenarios) {
                var state=new DialogueState();
                for(var test:scenario.get("turns")) {
                    String message=test.get("message").asText();long start=System.nanoTime();
                    Map<String,Object> result=new LinkedHashMap<>();result.put("scenario",scenario.get("name").asText());result.put("message",message);
                    try {
                        var mentions=planner.mentions(TestCatalog.USER1,message);
                        var interpreted=parser.interpret(message,new IntentParser.Context(state,catalog.dispatchableReports(TestCatalog.USER1).stream().map(CatalogEntry::ref).toList(),mentions));
                        var intent=interpreted.intent();result.put("parserSource",interpreted.source());
                        String outcome="READY";
                        try { planner.merge(TestCatalog.USER1,state,intent);planner.requireCoverage(state,intent,mentions);planner.requireAction(state,intent);planner.validate(TestCatalog.USER1,state);state.setEffective(state.getDesired());state.setPhase(DialogueState.Phase.READY); }
                        catch(ApiException e){outcome=e.getCode()==422?"CLARIFY":"REJECTED";state.setPhase(DialogueState.Phase.valueOf(outcome));state.setLastReason(e.getMessage());}
                        state.setPendingIntent(intent);state.setRecentUserMessages(List.of(message));
                        boolean actionCorrect=intent.action().name().equals(test.get("action").asText());
                        boolean passed=actionCorrect
                                && intent.company().operation().name().equals(test.get("companyOperation").asText())
                                && intent.reports().operation().name().equals(test.get("reportsOperation").asText())
                                && intent.exclusions().operation().name().equals(test.path("exclusionsOperation").asText("KEEP"))
                                && Objects.equals(state.getDesired().companyCode(),test.get("company").isNull()?null:test.get("company").asText())
                                && outcome.equals(test.get("outcome").asText());
                        if(test.has("reportIds")) passed &= new HashSet<>(state.getDesired().reportIds()).equals(JsonUtil.MAPPER.convertValue(test.get("reportIds"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){}));
                        result.put("actionCorrect",actionCorrect);
                        result.put("passed",passed);result.put("intent",intent);result.put("outcome",outcome);result.put("scope",state.getDesired());
                    } catch(Exception e){result.put("passed",false);result.put("error",e.getClass().getSimpleName());
                        if(e instanceof IntentCodec.InvalidOutput invalid) result.put("invalidOutput",invalid.output());
                        else if(e.getMessage()!=null) result.put("errorMessage",e.getMessage().replace(System.getenv("LLM_API_KEY"),"[redacted]"));
                        state.setUnresolvedCompany(true);state.setUnresolvedReports(true);state.setPhase(DialogueState.Phase.CLARIFY);
                        if(!(e instanceof ApiException)) {
                            result.put("latencyMs",(System.nanoTime()-start)/1_000_000);results.add(result);break evaluation;
                        }
                    }
                    result.put("latencyMs",(System.nanoTime()-start)/1_000_000);results.add(result);
                }
            }
        }
        long passed=results.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count();
        var sources=results.stream().filter(r->r.containsKey("parserSource")).collect(java.util.stream.Collectors.groupingBy(r->r.get("parserSource").toString(),java.util.stream.Collectors.counting()));
        var output=Map.of("model",model,"nativeSchema",props.getSemantic().isNativeSchema(),"thinkingEnabled",props.getSemantic().isThinkingEnabled(),"passed",passed,"total",results.size(),"parserSources",sources,"cases",results);
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/semantic-live-evaluation.json"),JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        assertEquals(results.size(),passed,"See target/semantic-live-evaluation.json (no business actions executed)");
    }
}
