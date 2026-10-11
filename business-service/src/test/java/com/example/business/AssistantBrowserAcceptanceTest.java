package com.example.business;

import com.example.report.common.JsonUtil;
import com.example.report.operations.SensitiveData;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.example.business.BusinessMcpIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 浏览器端到端验收的隔离服务夹具；真实模型、登录认证和HTTP MCP保持完整装配。
 * 产品动作必须经浏览器执行，文件命令只做数据库独立核对及结束服务；临时凭据存放于已限制访问的忽略目录。
 */
@EnabledIfEnvironmentVariable(named="ASSISTANT_BROWSER",matches="true")
class AssistantBrowserAcceptanceTest {
    private static Path directory;
    @BeforeAll static void startFixture() {
        for(String key:List.of("LLM_API_KEY","LLM_BASE_URL","LLM_MODEL","ASSISTANT_BROWSER_DIR"))
            assertFalse(System.getenv().getOrDefault(key,"").isBlank(),"浏览器验收配置缺失："+key);
        directory=Path.of(System.getenv("ASSISTANT_BROWSER_DIR")).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory),"先创建并限制本轮临时目录访问");
        AssistantMcpLiveReviewTest.clearModelEvidence();BusinessMcpIntegrationTest.start();
        // 整体回归使用同一隔离夹具中的A公司业务角色，覆盖三类报表而不获得跨公司或管理员权限。
        identities.saveUser(admin,new com.example.report.security.IdentityStore.UserForm("operatorA","Operator A",password,Set.of("A"),
                Set.of("report:sales","report:receivable","report:expense"),false,true));
        // 本轮精度检查只向当前已核实的隔离库加入合成事实，不修改 report_demo；高位分值必须完整到达真实浏览器。
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        jdbc.update("INSERT INTO report_sales(tenant_id,company_code,order_no,product_name,amount,sale_date,dispatch_status) VALUES ('T001','A','MONEY-EXACT-01','精度验收大额','90071992547409.93','2026-10-01',0),('T001','A','MONEY-EXACT-02','精度验收分位','0.01','2026-10-01',0)");
        // 合法金额可能与手机号具有相同数字形状；沿既定确认目标验证 HTTP、SSE、任务结果和历史的共同脱敏边界。
        assertEquals(1,jdbc.update("UPDATE report_sales SET amount=13800138000.00 WHERE tenant_id='T001' AND company_code='A' AND id=1"));
    }
    @AfterAll static void stopFixture() throws Exception {
        if(args!=null)BusinessMcpIntegrationTest.stop();
        if(directory!=null) {
            Files.deleteIfExists(directory.resolve("credentials.json"));
            Files.deleteIfExists(directory.resolve("regression-credentials.json"));
            Files.deleteIfExists(directory.resolve("admin-credentials.json"));
        }
    }

    @Test void browserControlsTheRealTaskFlow() throws Exception {
        var ready=directory.resolve("ready.json");var command=directory.resolve("command.json");
        Files.deleteIfExists(ready);Files.deleteIfExists(command);
        int agentPort=Integer.parseInt(System.getenv().getOrDefault("ASSISTANT_BROWSER_AGENT_PORT","0"));
        assertTrue(agentPort==0 || agentPort>=1024 && agentPort<=65535,"验收端口须为可用的非特权端口");
        var agentArgs=java.util.stream.Stream.concat(Arrays.stream(args).filter(a->!a.startsWith("--business.remote.enabled=") && !a.startsWith("--server.port=")),java.util.stream.Stream.of(
                "--server.port="+agentPort,
                "--business.remote.enabled=true","--business.remote.url="+base,"--spring.flyway.enabled=false",
                "--spring.ai.openai.api-key="+System.getenv("LLM_API_KEY"),"--spring.ai.openai.base-url="+System.getenv("LLM_BASE_URL"),
                "--spring.ai.openai.chat.options.model="+System.getenv("LLM_MODEL"),"--spring.ai.openai.chat.completions-path=/v1/chat/completions",
                "--agent.llm.mock=false","--agent.semantic.mode=active","--agent.semantic.model="+System.getenv("LLM_MODEL"),
                "--agent.semantic.thinking-enabled=false","--agent.semantic.native-schema=true","--agent.dispatch-jobs-poll-ms=100")).toArray(String[]::new);
        boolean finished=false;
        try(var agent=(ServletWebServerApplicationContext)new SpringApplicationBuilder(com.example.report.ReportApplication.class,AssistantMcpLiveReviewTest.ModelEvidence.class)
                .profiles("real","mcp").run(agentArgs)) {
            if("true".equals(System.getenv("SEMANTIC_P0")))prepareP0Dictionary(agent);
            Files.writeString(directory.resolve("credentials.json"),JsonUtil.toJson(Map.of("userId","readerA","password",password)));
            Files.writeString(directory.resolve("regression-credentials.json"),JsonUtil.toJson(Map.of("userId","operatorA","password",password,"serviceToken",secret)));
            Files.writeString(directory.resolve("admin-credentials.json"),JsonUtil.toJson(Map.of("userId","admin","password",password)));
            write(ready,Map.of("agentUrl","http://127.0.0.1:"+agent.getWebServer().getPort(),"businessUrl",base,"schema",schema,
                    "model",System.getenv("LLM_MODEL"),"profiles",List.of("real","mcp"),"semanticMode","active"));
            write(directory.resolve("initial.json"),snapshot("initial"));
            System.out.println("BROWSER_FIXTURE_DATABASE="+schema);
            // 整体真实模型回放可能长于人工专项；显式时限仍有上界，超时不计为通过。
            int minutes=Integer.parseInt(System.getenv().getOrDefault("ASSISTANT_BROWSER_MINUTES","90"));
            assertTrue(minutes>=1 && minutes<=240,"隔离验收时限必须为1至240分钟");
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(minutes);var handled=new HashSet<String>();
            while(!finished && System.nanoTime()<deadline) {
                if(!Files.exists(command)){Thread.sleep(200);continue;}
                var input=JsonUtil.MAPPER.readTree(Files.readString(command));String id=input.path("id").asText();
                if(!id.matches("[A-Za-z0-9_-]{1,64}") || !handled.add(id)){Thread.sleep(200);continue;}
                assertTrue(schema.matches("mcp_it_[a-f0-9]{32}"));assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
                String kind=input.path("type").asText();var result=snapshot(id);
                try {
                    switch(kind) {
                        case "readonly" -> {
                            assertEquals(0,count("dispatch_plan"));assertEquals(0,count("dispatch_preview"));assertEquals(0,count("business_dispatch_request"));
                        }
                        case "pending" -> {
                            assertEquals(0,count("business_dispatch_request"));
                            var records=jdbc.queryForList("SELECT i.record_id FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id WHERE p.user_id='readerA' AND p.status='PENDING' ORDER BY i.seq",String.class);
                            assertEquals(List.of("1"),records);assertEquals(0,jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=1",Integer.class));
                        }
                        case "snapshot" -> { }
                        case "diagnostics" -> result.put("modelCalls",AssistantMcpLiveReviewTest.modelEvidenceSnapshot());
                        case "finish" -> {
                            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE user_id='readerA' AND status='CANCELLED'",Integer.class)>0);
                            assertEquals(1,count("business_dispatch_request"));
                            assertEquals(1,jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=1",Integer.class));
                            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE user_id='readerA' AND confirmed_at IS NOT NULL AND status='EXECUTED' AND success_count=1",Integer.class)>0);
                            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM semantic_turn WHERE user_id='readerA' AND model=? AND latency_ms>0",Integer.class,System.getenv("LLM_MODEL"))>=4);
                            finished=true;
                        }
                        case "stop" -> {write(directory.resolve(id+".json"),result);return;}
                        default -> throw new IllegalArgumentException("未声明的验收控制命令");
                    }
                    result.put("databaseChecksPassed",true);
                } catch(Exception|AssertionError failure) {
                    result.put("databaseChecksPassed",false);result.put("error",SensitiveData.text(Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())));
                    write(directory.resolve(id+".json"),result);throw failure;
                }
                write(directory.resolve(id+".json"),result);
            }
            assertTrue(finished,"浏览器未完成流程，不能将等待或超时视为验收通过");
        } finally {
            var result=snapshot("final");result.put("browserWorkflowFinished",finished);
            result.put("modelCalls",AssistantMcpLiveReviewTest.modelEvidenceSnapshot());write(directory.resolve("backend-final.json"),result);
            Files.deleteIfExists(ready);
            if(!finished)throw new AssertionError("浏览器验收尚未完成，服务已结束并保留证据");
        }
    }

    /** 全部SQL由夹具固定，仅核对本轮专用库的来源、清单、条目及轮次；不接收浏览器或模型提供的SQL。 */
    private Map<String,Object> snapshot(String checkpoint) {
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        var result=new LinkedHashMap<String,Object>();result.put("checkpoint",checkpoint);result.put("schema",schema);
        result.put("recordedAt",OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        result.put("source",jdbc.queryForList("SELECT id,company_code,order_no,product_name,CAST(amount AS CHAR) AS amount,dispatch_status FROM report_sales ORDER BY id"));
        result.put("previews",jdbc.queryForList("SELECT id,conversation_id,user_id,status FROM dispatch_preview ORDER BY created_at"));
        result.put("plans",jdbc.queryForList("SELECT id,conversation_id,user_id,status,status_reason,item_count,success_count,failed_count,confirmed_at,execution_version FROM dispatch_plan ORDER BY created_at"));
        result.put("items",jdbc.queryForList("SELECT plan_id,record_id,doc_no,company_code,CAST(amount AS CHAR) AS amount,status,attempt_count,external_request_id,error_code FROM dispatch_plan_item ORDER BY id"));
        // 只读快照保留任务身份及执行版本，以便比较翻页、刷新与清单恢复前后是否产生额外执行；不输出凭据或原始提交载荷。
        result.put("jobs",jdbc.queryForList("SELECT id,plan_id,action,status,expected_version,(result_json IS NOT NULL) AS result_available,created_at,updated_at FROM dispatch_job ORDER BY created_at,id"));
        result.put("businessRequests",count("business_dispatch_request"));
        result.put("semanticTurns",jdbc.queryForList("SELECT conversation_id,utterance,outcome,reason,model,latency_ms FROM semantic_turn ORDER BY created_at"));
        return result;
    }
    private int count(String table) {
        if(!Set.of("dispatch_plan","dispatch_preview","business_dispatch_request").contains(table))throw new IllegalArgumentException("未声明的测试表");
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);
    }
    /** P0只在本轮隔离夹具发布已声明词典，经真实服务的权限、版本和审计路径生效，不改业务来源数据。 */
    private static void prepareP0Dictionary(ServletWebServerApplicationContext agent) {
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        var dictionaries=agent.getBean(com.example.report.assistant.SemanticDictionaryService.class);
        for(String company:List.of("A","B")) {
            var scope=new com.example.report.assistant.SemanticDictionary.Scope(com.example.report.assistant.BusinessQuery.Domain.REPORT,company,REPORT);
            var definition=new com.example.report.assistant.SemanticDictionary.Definition(company.equals("A")?"沪东公司":"沪南公司",List.of(company.equals("A")?"沪东":"沪南"),"Asia/Shanghai",4,List.of(),List.of(
                    new com.example.report.assistant.SemanticDictionary.Field("amount",List.of("签约额"),"销售来源金额，按元保存","元","CNY",List.of(new com.example.report.assistant.SemanticDictionary.Unit("万元","10000"),new com.example.report.assistant.SemanticDictionary.Unit("千元","1000")),List.of()),
                    new com.example.report.assistant.SemanticDictionary.Field("date",List.of("成交日期"),"销售业务日期","","",List.of(),List.of()),
                    new com.example.report.assistant.SemanticDictionary.Field("status",List.of("推送状态"),"来源记录是否已派单","","",List.of(),List.of(new com.example.report.assistant.SemanticDictionary.Value("未派单",List.of("待推送")),new com.example.report.assistant.SemanticDictionary.Value("已派单",List.of("已推送"))))));
            dictionaries.publish(admin,scope,new com.example.report.assistant.SemanticDictionaryService.Change(0,definition,"本轮已授权P0隔离验收业务词典"));
        }
    }
    private static void write(Path file,Object value) throws Exception {
        Files.writeString(file,JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(SensitiveData.value(value)));
    }
}
