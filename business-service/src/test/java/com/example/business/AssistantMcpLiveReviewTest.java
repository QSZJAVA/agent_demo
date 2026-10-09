package com.example.business;

import com.example.report.common.JsonUtil;
import com.example.report.operations.SensitiveData;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static com.example.business.BusinessMcpIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式启用的真实模型、登录会话与HTTP MCP联合回放；复用专用mcp_it库夹具并自动清理。
 * 从独立SQL核对对象及规则，流程止于待确认和取消；来源变化仅在本轮隔离库准备，不调用确认执行。
 */
@EnabledIfEnvironmentVariable(named="ASSISTANT_JOINT",matches="true")
class AssistantMcpLiveReviewTest {
    private static final List<Map<String,Object>> modelCalls=Collections.synchronizedList(new ArrayList<>());
    /** 浏览器夹具复用真实模型旁路取证；清空或复制诊断不改变模型输入、返回值及业务状态。 */
    static void clearModelEvidence(){modelCalls.clear();}
    static List<Map<String,Object>> modelEvidenceSnapshot(){
        // 活跃浏览器验收也可只读取证；在同一监视器内复制每条记录，避免响应完成时修改正在序列化的Map。
        synchronized(modelCalls){return modelCalls.stream().map(call->Collections.unmodifiableMap(new LinkedHashMap<>(call))).toList();}
    }
    private final List<Map<String,Object>> evidence=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String agentUrl,token,conversationId;

    @BeforeAll static void startFixture() {
        modelCalls.clear();
        for(String name:List.of("LLM_API_KEY","LLM_BASE_URL","LLM_MODEL"))
            assertFalse(System.getenv().getOrDefault(name,"").isBlank(),"真实模型配置缺失："+name);
        BusinessMcpIntegrationTest.start();
    }
    @AfterAll static void stopFixture() {if(args!=null)BusinessMcpIntegrationTest.stop();}

    @Test void realModelQueryEligibilityPreparationRefreshAndSourceChangeStayBound() throws Exception {
        var output=new LinkedHashMap<String,Object>();
        output.put("model",System.getenv("LLM_MODEL"));output.put("profiles",List.of("real","mcp"));output.put("semanticMode","active");
        output.put("scope","真实模型规划与复核、真实登录会话、HTTP MCP和隔离MySQL；未确认执行、未运行浏览器、未连接外部ERP");
        output.put("cases",evidence);output.put("passed",false);output.put("databaseSchema",schema);
        var agentArgs=java.util.stream.Stream.concat(Arrays.stream(args).filter(a->!a.startsWith("--business.remote.enabled=")),java.util.stream.Stream.of(
                "--business.remote.enabled=true","--business.remote.url="+base,"--spring.flyway.enabled=false",
                "--spring.ai.openai.api-key="+System.getenv("LLM_API_KEY"),"--spring.ai.openai.base-url="+System.getenv("LLM_BASE_URL"),
                "--spring.ai.openai.chat.options.model="+System.getenv("LLM_MODEL"),"--spring.ai.openai.chat.completions-path=/v1/chat/completions",
                "--agent.llm.mock=false","--agent.semantic.mode=active","--agent.semantic.model="+System.getenv("LLM_MODEL"),
                "--agent.semantic.thinking-enabled=false","--agent.semantic.native-schema=true")).toArray(String[]::new);
        try(var agent=(ServletWebServerApplicationContext)new SpringApplicationBuilder(com.example.report.ReportApplication.class,ModelEvidence.class).profiles("real","mcp").run(agentArgs)) {
            agentUrl="http://127.0.0.1:"+agent.getWebServer().getPort();
            var login=http.send(HttpRequest.newBuilder(URI.create(agentUrl+"/api/auth/login")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("userId","readerA","password",password)))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,login.statusCode());token=json.readTree(login.body()).path("data").path("token").asText();assertFalse(token.isBlank());
            var expected=jdbc.queryForList("SELECT CAST(id AS CHAR) AS recordId,amount FROM report_sales WHERE tenant_id='T001' AND company_code='A' AND product_name='服务器'");
            assertEquals(1,expected.size());String recordId=expected.get(0).get("recordId").toString();
            int ruleVersion=jdbc.queryForObject("SELECT version FROM dispatch_rule WHERE id=1",Integer.class);
            step("查一下销售报表服务器的数据",events->{
                var result=query(events);assertEquals("LIST",result.path("query").path("view").asText());
                assertEquals(1,result.path("total").asInt());assertEquals(recordId,result.path("rows").get(0).path("recordId").asText());
                assertEquals(0,count("dispatch_preview"));assertEquals(0,count("dispatch_plan"));
            });
            step("这条数据符合派单条件吗",events->{
                var result=query(events);assertEquals("ELIGIBILITY",result.path("query").path("view").asText());
                var row=result.path("rows").get(0);assertEquals(recordId,row.path("recordId").asText());
                assertTrue(row.path("eligibility").path("eligible").asBoolean());assertEquals(ruleVersion,row.path("eligibility").path("ruleVersion").asInt());
                assertEquals(0,new java.math.BigDecimal(expected.get(0).get("amount").toString()).compareTo(new java.math.BigDecimal(row.path("amount").asText())));
                assertEquals(0,count("dispatch_preview"));assertEquals(0,count("dispatch_plan"));
            });
            step("帮我派单这条",events->{assertTrue(events.containsKey("preview"));assertTrue(events.containsKey("plan"));assertPending(recordId);});
            String original=jdbc.queryForObject("SELECT id FROM dispatch_plan WHERE conversation_id=? AND status='PENDING'",String.class,conversationId);
            step("重新核对当前候选",events->{
                assertTrue(events.containsKey("preview"));assertFalse(events.containsKey("plan"));
                assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM dispatch_plan WHERE id=?",String.class,original));
                assertEquals(List.of(recordId),jdbc.queryForList("SELECT i.record_id FROM dispatch_preview_item i JOIN dispatch_preview p ON p.id=i.preview_id WHERE p.conversation_id=? AND p.status='ACTIVE' ORDER BY i.seq",String.class,conversationId));
            });
            step("为当前候选整理成待确认清单",events->{assertTrue(events.containsKey("plan"));assertPending(recordId);});
            step("撤销当前清单",events->assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE conversation_id=? AND status='CANCELLED'",Integer.class,conversationId)));
            // 回读HTTP历史验证卡片投影与当前清单状态；不把程序HTTP回放计为浏览器验收。
            var restored=http.send(HttpRequest.newBuilder(URI.create(agentUrl+"/api/agent/conversations/"+conversationId+"/messages"))
                    .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,restored.statusCode());assertTrue(json.readTree(restored.body()).path("data").size()>0);
            output.put("historyRestored",true);
            assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));assertEquals(0,count("business_dispatch_request"));
            assertEquals(0,jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=?",Integer.class,recordId));
            jdbc.update("UPDATE report_sales SET dispatch_status=1 WHERE tenant_id='T001' AND company_code='A' AND id=?",recordId);
            step("不用派单，只核验刚才那条当前是否符合规则",events->{
                var result=query(events);assertEquals("ELIGIBILITY",result.path("query").path("view").asText());
                var row=result.path("rows").get(0);assertEquals(recordId,row.path("recordId").asText());assertFalse(row.path("eligibility").path("eligible").asBoolean());
                assertTrue(row.path("eligibility").path("reason").asText().contains("已派单"));
            });
            int plansBefore=count("dispatch_plan"),previewsBefore=count("dispatch_preview");
            step("帮我派单这条",events->{
                assertFalse(events.containsKey("preview"));assertFalse(events.containsKey("plan"));
                assertEquals(plansBefore,count("dispatch_plan"));assertEquals(previewsBefore,count("dispatch_preview"));
            });
            assertEquals(0,count("business_dispatch_request"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE confirmed_at IS NOT NULL",Integer.class));
            output.put("semanticTurns",jdbc.queryForList("SELECT outcome,reason,model,latency_ms FROM semantic_turn WHERE conversation_id=? ORDER BY created_at",conversationId));
            output.put("plans",jdbc.queryForList("SELECT status,item_count,confirmed_at FROM dispatch_plan WHERE conversation_id=? ORDER BY created_at",conversationId));
            output.put("sourceChangedOnlyByFixture",true);output.put("passed",true);
        } catch(Exception|AssertionError failure) {
            output.put("error",SensitiveData.text(Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())));throw failure;
        } finally {
            output.put("recordedAt",OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
            output.put("modelCalls",modelEvidenceSnapshot());
            Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target","assistant-mcp-live-review.json"),JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(SensitiveData.value(output)));
        }
    }

    /** 每步走真实认证HTTP与完整SSE，再用独立固定断言核验；失败立即留证，不把后续前提缺失计为成功。 */
    private void step(String message,Consumer<Map<String,JsonNode>> verify) throws Exception {
        var result=new LinkedHashMap<String,Object>();result.put("message",message);result.put("passed",false);evidence.add(result);
        var request=new LinkedHashMap<String,Object>();request.put("message",message);request.put("conversationId",conversationId);
        var response=http.send(HttpRequest.newBuilder(URI.create(agentUrl+"/api/agent/chat")).timeout(Duration.ofSeconds(190))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());var events=new LinkedHashMap<String,JsonNode>();var all=new ArrayList<Map<String,Object>>();
        for(String block:response.body().replace("\r\n","\n").split("\n\n")) {
            String type=null,data=null;
            for(String line:block.split("\n")) {if(line.startsWith("event:"))type=line.substring(6).trim();if(line.startsWith("data:"))data=line.substring(5).trim();}
            if(type!=null && data!=null) {var value=json.readTree(data);events.put(type,value);all.add(Map.of("type",type,"data",value));}
        }
        result.put("events",all);assertTrue(events.containsKey("done"));assertFalse(events.containsKey("error"),all.toString());
        conversationId=events.get("conversation").path("conversationId").asText();assertFalse(conversationId.isBlank());
        verify.accept(events);result.put("passed",true);
    }
    private JsonNode query(Map<String,JsonNode> events) {
        assertTrue(events.containsKey("business_query"),events.toString());assertFalse(events.containsKey("plan"));assertFalse(events.containsKey("preview"));
        return events.get("business_query");
    }
    /** SQL对象范围由本夹具固定，绝不接收模型字段；待确认条目必须完整等于独立来源定位的唯一记录。 */
    private void assertPending(String recordId) {
        var items=jdbc.queryForList("SELECT i.record_id FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id WHERE p.conversation_id=? AND p.status='PENDING'",String.class,conversationId);
        assertEquals(List.of(recordId),items);assertEquals(0,count("business_dispatch_request"));
    }
    private int count(String table) {
        if(!Set.of("dispatch_preview","dispatch_plan","business_dispatch_request").contains(table))throw new IllegalArgumentException("未声明的测试表");
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);
    }

    /** 只旁路记录真实模型入参和结果，保留实际HTTP调用与原有模型选项；不替换响应、不采集认证配置。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ModelEvidence {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor captureModelEvidence() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(!(bean instanceof org.springframework.ai.chat.model.ChatModel delegate))return bean;
                    return new org.springframework.ai.chat.model.ChatModel() {
                        @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions(){return delegate.getDefaultOptions();}
                        @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                            var call=new LinkedHashMap<String,Object>();call.put("input",JsonUtil.toMap(prompt.getUserMessage().getText()));modelCalls.add(call);
                            var response=delegate.call(prompt);
                            synchronized(modelCalls){call.put("output",SensitiveData.text(response.getResult().getOutput().getText()));}return response;
                        }
                        @Override public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt) {
                            return delegate.stream(prompt);
                        }
                    };
                }
            };
        }
    }
}
