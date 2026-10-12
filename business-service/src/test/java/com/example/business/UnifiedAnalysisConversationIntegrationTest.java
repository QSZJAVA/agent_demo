package com.example.business;

import com.example.report.ReportApplication;
import com.example.report.analysis.*;
import com.example.report.assistant.*;
import com.example.report.catalog.CatalogEntry;
import com.example.report.common.*;
import com.example.report.conversation.ConversationService;
import com.example.report.operations.DataRetentionService;
import com.example.report.operations.SensitiveData;
import com.example.report.permission.CurrentUser;
import com.example.report.security.IdentityStore;
import com.example.report.semantic.*;
import com.example.report.workspace.*;
import com.example.report.trace.TraceProjector;
import com.example.report.mapper.AgentMessageMapper;
import com.example.report.mapper.DispatchAuditMapper;
import com.example.report.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一聊天接续分析的专用隔离库验收：真实登录与HTTP MCP贯穿所有场景，受控模型只验证状态流转。
 * 真实模型专项委派原传输且没有替代结果；每例失败事实和调用证据即时落盘，保留既有数据库和浏览器标签。
 */
@EnabledIfEnvironmentVariable(named="UNIFIED_ANALYSIS",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UnifiedAnalysisConversationIntegrationTest {
    /** 仅本次进程生成的数据库可创建和清理，不复用或改名report_demo。 */
    static final String SCHEMA="unified_analysis_it_"+UUID.randomUUID().toString().replace("-","");
    static final String REPORT="rpt-sales-order",PASSWORD=UUID.randomUUID().toString(),SECRET=UUID.randomUUID()+"-"+UUID.randomUUID();
    static final List<Map<String,Object>> DATABASES=new ArrayList<>(),CASES=new ArrayList<>();
    static final List<Map<String,Object>> PLANNER_CALLS=new CopyOnWriteArrayList<>(),ANALYSIS_CALLS=new CopyOnWriteArrayList<>(),HTTP_MCP_QUERIES=new CopyOnWriteArrayList<>(),HTTP_EVENTS=new CopyOnWriteArrayList<>();
    static ServletWebServerApplicationContext business,agent;
    static JdbcTemplate master,jdbc;
    static IdentityStore identities;
    static CurrentUser admin,user,other;
    static AnalysisService analyses;
    static AnalysisWorker worker;
    static KnowledgeService knowledge;
    static ControlledPlanner planner;
    static EvidenceAnalysisModel analysisModel;
    static CheckpointAnalysisService checkpoint;
    static BarrierReceiptStore receipts;
    static DelayedTraceProjector projector;
    static Path directory;
    static String agentUrl,businessUrl,token;
    static List<String> baseline;
    static boolean created;
    static int caseNumber;
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @RegisterExtension TestWatcher watcher=new TestWatcher() {
        @Override public void testSuccessful(ExtensionContext context){record(context,true,null);}
        @Override public void testFailed(ExtensionContext context,Throwable failure){record(context,false,Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()));}
        private void record(ExtensionContext context,boolean passed,String failure) {
            var row=new LinkedHashMap<String,Object>();row.put("test",context.getDisplayName());row.put("passed",passed);
            row.put("error",SensitiveData.text(failure));row.put("recordedAt",Instant.now().toString());CASES.add(row);
            write("integration-results.json",Map.of("schema",SCHEMA,"cases",CASES,"profiles",List.of("real","mcp"),"semanticMode","active"));persistEvidence();
        }
    };

    /** 授权开关与准确新库名称同时验证；启动参数只从当前本机配置取值，凭据不输出到证据。 */
    @BeforeAll static void start() throws Exception {
        assertEquals("true",System.getenv("UNIFIED_ALLOW_ISOLATED_DATABASES"),"隔离库创建、写入和清理须显式启用已授权开关");
        directory=Path.of(Objects.requireNonNull(System.getenv("UNIFIED_ANALYSIS_DIR"))).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory));
        for(String name:List.of("LLM_API_KEY","LLM_BASE_URL","LLM_MODEL"))assertFalse(env(name,"").isBlank(),"真实模型配置缺失："+name);
        String base="jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/";
        master=new JdbcTemplate(new DriverManagerDataSource(base+"?serverTimezone=Asia/Shanghai",env("DB_USERNAME","root"),env("DB_PASSWORD","")));
        baseline=master.queryForList("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME",String.class);
        assertFalse(baseline.contains(SCHEMA));assertTrue(SCHEMA.matches("unified_analysis_it_[a-f0-9]{32}"));
        DATABASES.add(Map.of("event","before","existing",baseline,"newSchema",SCHEMA,"at",Instant.now().toString()));saveDatabases();
        master.execute("CREATE DATABASE `"+SCHEMA+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");created=true;
        DATABASES.add(Map.of("event","created","schema",SCHEMA,"at",Instant.now().toString()));saveDatabases();
        var args=new ArrayList<>(List.of("--server.port=0","--spring.datasource.url="+base+SCHEMA+"?serverTimezone=Asia/Shanghai",
                "--spring.datasource.username="+env("DB_USERNAME","root"),"--spring.datasource.password="+env("DB_PASSWORD",""),
                "--spring.data.redis.host="+env("REDIS_HOST","127.0.0.1"),"--spring.data.redis.port="+env("REDIS_PORT","6379"),
                "--spring.data.redis.password="+env("REDIS_PASSWORD",""),"--business.service-token="+SECRET,"--security.bootstrap-password="+PASSWORD,
                "--security.enabled=true","--agent.retention-sweep-ms=3600000","--agent.trace-retry-ms=3600000","--logging.level.root=WARN"));
        business=(ServletWebServerApplicationContext)new SpringApplicationBuilder(BusinessApplication.class,BusinessEvidenceConfiguration.class)
                .properties("spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.model.image=none","spring.ai.model.moderation=none","spring.ai.model.audio.speech=none","spring.ai.model.audio.transcription=none")
                .run(args.toArray(String[]::new));
        jdbc=business.getBean(JdbcTemplate.class);assertEquals(SCHEMA,jdbc.queryForObject("SELECT DATABASE()",String.class));
        identities=business.getBean(IdentityStore.class);admin=identities.resolve("T001","admin");
        businessUrl="http://127.0.0.1:"+business.getWebServer().getPort();
        args.removeIf(arg->arg.startsWith("--server.port="));args.add("--server.port="+env("UNIFIED_AGENT_PORT","0"));
        args.addAll(List.of("--spring.flyway.enabled=false","--business.remote.enabled=true","--business.remote.url="+businessUrl,
                "--agent.llm.mock=false","--agent.semantic.mode=active","--agent.semantic.native-schema=true","--agent.semantic.thinking-enabled=false",
                "--spring.ai.openai.api-key="+System.getenv("LLM_API_KEY"),"--spring.ai.openai.base-url="+System.getenv("LLM_BASE_URL"),
                "--spring.ai.openai.chat.options.model="+System.getenv("LLM_MODEL"),"--agent.analysis.initial-ms=3600000","--agent.analysis.poll-ms=3600000",
                "--agent.query-export-initial-ms=3600000"));
        agent=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ReportApplication.class,AgentEvidenceConfiguration.class,AssistantMcpLiveReviewTest.ModelEvidence.class)
                .profiles("real","mcp").run(args.toArray(String[]::new));
        agentUrl="http://127.0.0.1:"+agent.getWebServer().getPort();analyses=agent.getBean(AnalysisService.class);worker=agent.getBean(AnalysisWorker.class);
        knowledge=agent.getBean(KnowledgeService.class);planner=agent.getBean(ControlledPlanner.class);analysisModel=agent.getBean(EvidenceAnalysisModel.class);
        checkpoint=agent.getBean(CheckpointAnalysisService.class);receipts=agent.getBean(BarrierReceiptStore.class);
        projector=agent.getBean(DelayedTraceProjector.class);
        write("fixture.json",Map.of("schema",SCHEMA,"agentUrl",agentUrl,"businessUrl",businessUrl,"model",System.getenv("LLM_MODEL"),
                "profiles",List.of("real","mcp"),"semanticMode","active","scope","工程合成资料和本仓库专用数据库；真实HTTP MCP，无外部ERP或生产准入验收"));
    }

    /** 只删除本次实际创建且不在启动前清单中的库；关闭本夹具服务不操作任何浏览器标签或窗口。 */
    @AfterAll static void stop() throws Exception {
        if(planner!=null)planner.release.countDown();
        try {if(agent!=null)agent.close();} finally {if(business!=null)business.close();}
        if(created) {
            assertTrue(SCHEMA.matches("unified_analysis_it_[a-f0-9]{32}"));assertFalse(baseline.contains(SCHEMA));
            master.execute("DROP DATABASE `"+SCHEMA+"`");var after=master.queryForList("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME",String.class);
            assertFalse(after.contains(SCHEMA));assertTrue(after.containsAll(baseline));
            DATABASES.add(Map.of("event","removed","schema",SCHEMA,"remaining",after,"preexistingPreserved",true,"at",Instant.now().toString()));saveDatabases();
        }
        if(directory!=null)persistEvidence();
    }

    /** 每例使用独立用户避免跨例会话与配额污染；仅在前例执行者结束后复位本隔离库的来源事实。 */
    @BeforeEach void reset() throws Exception {
        planner.release.countDown();awaitWorkerIdle();planner.reset();analysisModel.live=false;analysisModel.configurationRevision="";
        analysisModel.plan=knowledgePlan("销售金额");checkpoint.failure.set("");receipts.disarm();projector.delayUser=null;
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT DATABASE()",String.class));
        for(String table:List.of("business_analysis_run","business_knowledge_document","business_query_run"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM report_sales");var seed=new ArrayList<Object[]>();
        for(int i=0;i<120;i++)seed.add(new Object[]{1000+i,"T001","A","UA-"+i,i==1?"=1+1":i%2==0?"Alpha":"alpha ",new BigDecimal(i+".01"),java.sql.Date.valueOf("2026-09-"+String.format("%02d",1+i%28)),0});
        seed.add(new Object[]{2000,"T001","B","UA-B","其他公司",new BigDecimal("999999"),java.sql.Date.valueOf("2026-09-01"),0});
        seed.add(new Object[]{3000,"OTHER","A","UA-OTHER","其他租户",new BigDecimal("999999"),java.sql.Date.valueOf("2026-09-01"),0});
        jdbc.batchUpdate("INSERT INTO report_sales(id,tenant_id,company_code,order_no,product_name,amount,sale_date,dispatch_status) VALUES (?,?,?,?,?,?,?,?)",seed);
        String name="ua_"+SCHEMA.substring(SCHEMA.length()-8)+"_"+(++caseNumber);
        identities.saveUser(admin,new IdentityStore.UserForm(name,"本例统一聊天用户",PASSWORD,Set.of("A"),Set.of("report:sales"),false,true));
        identities.saveUser(admin,new IdentityStore.UserForm(name+"_other","同权限另一用户",PASSWORD,Set.of("A"),Set.of("report:sales"),false,true));
        user=identities.resolve("T001",name);other=identities.resolve("T001",name+"_other");token=login(name);
        useAnalysis(AnalysisContract.Mode.KNOWLEDGE_ONLY);respectAnalysisRate();
    }
    @AfterEach void releaseBarriers() throws Exception {planner.release.countDown();receipts.disarm();projector.delayUser=null;projector.recover();awaitWorkerIdle();noBusinessWrites();persistEvidence();}

    @Test @Order(1) void missingAndInvalidClientKeysCannotCreateConversations() throws Exception {
        String body=JsonUtil.toJson(input(null,"销售金额的定义是什么？"));
        var missing=http(token,"POST","/api/agent/chat",body,Map.of());assertNotEquals(0,code(missing),missing.body());
        for(String key:List.of("short","bad.key.123","x".repeat(101)))assertEquals(422,code(http(token,"POST","/api/agent/chat",body,Map.of("Idempotency-Key",key))));
        assertEquals(0,count("agent_conversation"));assertEquals(0,count("chat_turn_receipt"));assertEquals(0,planner.calls.get());
    }

    @Test @Order(2) void sameCompletePayloadReplaysAndAnyPayloadChangeConflicts() throws Exception {
        publish("销售金额定义","销售金额是订单约定价款，不代表收款。");String key=key();var input=input(null,"销售金额是否等于已收款？");
        var first=chat(key,input);var ref=task(first);String id=ref.path("runId").asText();assertCompleted(id);
        int calls=planner.calls.get(),analysisCalls=ANALYSIS_CALLS.size();var again=chat(key,input);
        assertEquals(ref,task(again));assertEquals(calls,planner.calls.get());assertEquals(analysisCalls,ANALYSIS_CALLS.size());
        var alteredMessage=new LinkedHashMap<>(input);alteredMessage.put("message","另一条原文");
        var alteredPreview=new LinkedHashMap<>(input);alteredPreview.put("previewId","different-preview");
        var alteredExclusions=new LinkedHashMap<>(input);alteredExclusions.put("excludedRecords",List.of());
        var generatedConversation=new LinkedHashMap<>(input);generatedConversation.put("conversationId",event(first,"conversation").path("conversationId").asText());
        for(var changed:List.of(alteredMessage,alteredPreview,alteredExclusions,generatedConversation))assertEquals(409,code(post(key,changed)));
        assertEquals(1,count("agent_conversation"));assertEquals(1,count("chat_turn_receipt"));assertEquals(1,count("business_analysis_run"));assertEquals(1,cards());
        assertEquals(0,count("business_query_run"));assertEquals(0,mcpQueries());
    }

    @Test @Order(3) void simultaneousFirstReceiveCommitsOnlyOneConversationAndReceipt() throws Exception {
        String key=key();var body=new ChatTurnReceiptStore.Input(null,"并发接收原文",null,null,null,null);var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<ChatTurnReceiptStore.Accepted> action=()->{start.await();return receipts.receive(user,"TEST_DOUBLE",key,body);};
            var first=pool.submit(action);var second=pool.submit(action);start.countDown();var a=first.get(15,TimeUnit.SECONDS);var b=second.get(15,TimeUnit.SECONDS);
            assertEquals(a.row().get("request_id"),b.row().get("request_id"));assertEquals(a.row().get("conversation_id"),b.row().get("conversation_id"));assertNotEquals(a.replayed(),b.replayed());
            assertEquals(1,count("agent_conversation"));assertEquals(1,count("chat_turn_receipt"));assertEquals(0,planner.calls.get());
            write(caseFile("receive-race"),Map.of("first",receipt(key),"conversationCount",count("agent_conversation"),"receiptCount",count("chat_turn_receipt")));
        } finally {start.countDown();pool.shutdownNow();}
    }

    @Test @Order(4) void concurrentReceivedSubscriptionsCannotFailTheWinningPlanner() throws Exception {
        publish("销售金额定义","销售金额表示订单价款。");String key=key();receipts.arm(key);planner.block=true;
        var body=input(null,"销售金额的定义是什么？");var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->chat(key,body));var second=pool.submit(()->chat(key,body));
            assertTrue(planner.entered.await(15,TimeUnit.SECONDS),"胜出订阅应进入受控规划屏障");
            long until=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(!first.isDone()&&!second.isDone()&&System.nanoTime()<until)Thread.sleep(30);
            assertTrue(first.isDone()||second.isDone(),"竞争订阅应返回忙碌回执，不等待胜出规划完成");
            var during=receipt(key);assertEquals("PLANNING",during.path("status").asText());assertEquals("PLANNING",rawReceipt(key).get("status"));assertEquals(1,planner.calls.get());
            planner.release.countDown();var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
            assertEquals(event(a,"conversation").path("requestId"),event(b,"conversation").path("requestId"));
            var completed=receipt(key);assertEquals("SUBMITTED",completed.path("status").asText());assertCompleted(completed.path("analysisTask").path("runId").asText());
            assertEquals(1,count("agent_conversation"));assertEquals(1,count("chat_turn_receipt"));assertEquals(1,count("business_analysis_run"));assertEquals(1,cards());assertEquals(1,planner.calls.get());
            write(caseFile("subscription-race"),Map.of("receivedReaders",receipts.receivedReaderCount(),"whileWinnerBlocked",during,"after",completed));
        } finally {planner.release.countDown();receipts.disarm();pool.shutdownNow();}
    }

    @Test @Order(5) void readyCheckpointSurvivesInterruptionAndExplicitReplaySkipsPlanning() throws Exception {
        publish("销售金额定义","销售金额是订单价款，不能证明已收款。");String key=key();var body=input(null,"销售金额是否等于收款？\n请保留这句完整限定：只查现行制度。");
        checkpoint.failure.set("BEFORE_SUBMIT");var first=chat(key,body);assertTrue(hasEvent(first,"error"));
        var before=receipt(key);assertEquals("READY",before.path("status").asText());assertTrue(before.path("canReconcile").asBoolean());assertEquals(0,count("business_analysis_run"));
        var frozen=rawReceipt(key);assertEquals(body.get("message"),JsonUtil.MAPPER.readTree(frozen.get("trusted_request_json").toString()).path("question").asText());
        int calls=planner.calls.get();for(int i=0;i<3;i++)assertEquals(before,receipt(key));assertEquals(calls,planner.calls.get());
        var recovered=chat(key,body);var ref=task(recovered);assertCompleted(ref.path("runId").asText());
        assertEquals(calls,planner.calls.get());assertEquals(1,count("business_analysis_run"));assertEquals(1,cards());assertEquals(frozen.get("trusted_request_json"),rawReceipt(key).get("trusted_request_json"));
        write(caseFile("ready-recovery"),Map.of("before",before,"after",receipt(key),"plannerCalls",calls));
    }

    @Test @Order(6) void readyRecoveryRejectsPublishedKnowledgeChangeWithoutReplanning() throws Exception {
        var doc=publish("旧知识依据","销售金额是订单价款。");String key=ready("销售金额的口径是什么？");var before=rawReceipt(key);int calls=planner.calls.get();
        knowledge.transition(admin,doc.get("id").toString(),new KnowledgeService.Transition(2,"隔离测试停用已复核依据"),false);
        var projected=receipt(key);assertEquals("FAILED",projected.path("status").asText());assertFalse(projected.path("canReconcile").asBoolean());assertEquals(before,rawReceipt(key),"GET失效投影不能修改原回执");
        var response=chat(key,input(null,"销售金额的口径是什么？"));assertTrue(hasEvent(response,"error"));
        assertEquals("FAILED",receipt(key).path("status").asText());assertEquals("FAILED",rawReceipt(key).get("status"));assertEquals(before.get("trusted_request_json"),rawReceipt(key).get("trusted_request_json"));
        assertEquals(before.get("scope_hash"),rawReceipt(key).get("scope_hash"));assertEquals(calls,planner.calls.get());assertEquals(0,count("business_analysis_run"));
    }

    @Test @Order(7) void readyRecoveryRejectsChangedConfigurationWithoutAnEndlessReconcileLoop() throws Exception {
        String message="销售金额的口径是什么？",key=ready(message);var before=rawReceipt(key);int calls=planner.calls.get();
        analysisModel.configurationRevision="configuration-changed-after-ready";var projected=receipt(key);assertEquals("FAILED",projected.path("status").asText());assertFalse(projected.path("canReconcile").asBoolean());assertEquals(before,rawReceipt(key));
        var response=chat(key,input(null,message));assertTrue(hasEvent(response,"error"));assertEquals("FAILED",receipt(key).path("status").asText());assertEquals(before.get("config_hash"),rawReceipt(key).get("config_hash"));
        assertEquals(calls,planner.calls.get());assertEquals(0,count("business_analysis_run"));analysisModel.configurationRevision="";
        var replay=chat(key,input(null,message));assertEquals("FAILED",event(replay,"turn_receipt").path("status").asText());assertFalse(hasEvent(replay,"analysis_task"));assertEquals(calls,planner.calls.get());
    }

    @Test @Order(8) void expiredPlanningIsReadOnlyInterruptedAndCannotReplayTheModel() throws Exception {
        String key=key(),message="中断时没有可信分析方案";var accepted=receipts.receive(user,"TEST_DOUBLE",key,new ChatTurnReceiptStore.Input(null,message,null,null,null,null));
        String requestId=accepted.row().get("request_id").toString();
        jdbc.update("UPDATE chat_turn_receipt SET status='PLANNING',processing_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE request_id=?",requestId);
        var before=rawReceipt(key);var projected=receipt(key);assertEquals("INTERRUPTED",projected.path("status").asText());assertFalse(projected.path("canReconcile").asBoolean());
        for(int i=0;i<2;i++){var response=chat(key,input(null,message));assertEquals("INTERRUPTED",event(response,"turn_receipt").path("status").asText());}
        assertEquals(before,rawReceipt(key),"只读投影和原POST回放均不能夺取已失租规划");assertEquals(0,planner.calls.get());assertEquals(0,count("business_analysis_run"));
    }

    @Test @Order(9) void discardedEntireResponseRecoversOriginalTaskAndConversationByKey() throws Exception {
        publish("销售金额定义","销售金额不等于已收款。");String key=key();var body=input(null,"销售金额可以证明到账吗？");
        var response=HTTP.send(request(token,"POST","/api/agent/chat",JsonUtil.toJson(body),Map.of("Idempotency-Key",key)),HttpResponse.BodyHandlers.discarding());
        assertEquals(200,response.statusCode());var recovered=receipt(key);assertEquals("SUBMITTED",recovered.path("status").asText());
        String run=recovered.path("analysisTask").path("runId").asText();assertCompleted(run);int calls=planner.calls.get(),analysisCalls=ANALYSIS_CALLS.size();
        var replay=chat(key,body);assertEquals(recovered.path("analysisTask"),task(replay));assertEquals(calls,planner.calls.get());assertEquals(analysisCalls,ANALYSIS_CALLS.size());
        assertEquals(1,count("agent_conversation"));assertEquals(1,count("business_analysis_run"));assertEquals(1,cards());
        write(caseFile("discarded-response"),Map.of("faultScope","测试客户端主动丢弃完整SSE正文；不是操作系统网络故障复现","recovered",recovered,"sameKeyReplay",event(replay,"turn_receipt")));
    }

    @Test @Order(10) void failureAfterCommittedAssociationCannotLoseOrDuplicateTheTaskCard() throws Exception {
        checkpoint.failure.set("AFTER_SUBMIT");String key=key();var body=input(null,"销售金额的定义是什么？");var first=chat(key,body);assertTrue(hasEvent(first,"error"));
        var receipt=receipt(key);assertEquals("SUBMITTED",receipt.path("status").asText());assertEquals(receipt.path("analysisTask"),task(first));
        assertEquals(task(first),task(chat(key,body)));assertEquals(1,planner.calls.get());assertEquals(1,count("business_analysis_run"));assertEquals(1,cards());
    }

    @Test @Order(11) void snapshotContinuationUsesOriginalRowsAfterSourceAmountsChange() throws Exception {
        var query=queryConversation(normalQuery());String source=query.path("snapshot").path("id").asText();
        jdbc.update("UPDATE report_sales SET amount=amount+10000,product_name='changed' WHERE tenant_id='T001' AND company_code='A'");
        useAnalysis(AnalysisContract.Mode.SNAPSHOT_ONLY);analysisModel.plan=dataPlan(true);
        var result=chat(key(),input(query.path("conversationId").asText(),"只分析刚才查询的原始快照，按产品汇总并展开前两组。"));
        var run=assertCompleted(task(result).path("runId").asText());assertEquals(source,run.path("request").path("snapshotId").asText());
        assertEquals("SNAPSHOT_ONLY",run.path("request").path("mode").asText());assertEquals(source,step(run,"SNAPSHOT").path("result").path("snapshotId").asText());
        assertEquals(120,step(run,"GROUP").path("result").path("count").asInt());assertEquals(119,step(run,"DRILLDOWN").path("result").path("count").asInt());
        assertAmountSum(step(run,"GROUP").path("result").path("groups"),"7141.20");assertEquals(1,count("business_query_run"));assertEquals(1,mcpQueries());
        write(caseFile("snapshot-preserved"),Map.of("original",query,"analysis",run,"httpMcpBusinessQueries",mcpQueries()));
    }

    @Test @Order(12) void expiredMissingAndMismatchedSnapshotSourcesCannotReadCurrentRows() throws Exception {
        for(String defect:List.of("EXPIRED","MISSING_ROWS","WRONG_REFERENCE","WRONG_SCOPE")) {
            var source=queryConversation(normalQuery());String snapshot=source.path("snapshot").path("id").asText();int before=count("business_query_run"),calls=mcpQueries();
            useAnalysis(AnalysisContract.Mode.SNAPSHOT_ONLY);analysisModel.plan=dataPlan(true);
            if(defect.equals("EXPIRED"))jdbc.update("UPDATE business_query_run SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",snapshot);
            if(defect.equals("MISSING_ROWS"))jdbc.update("DELETE FROM business_query_row WHERE run_id=? AND seq=2",snapshot);
            if(defect.equals("WRONG_REFERENCE"))planner.factory=(state,context)->AssistantPlan.analysis(new AnalysisDirective("A",REPORT,AnalysisContract.Mode.SNAPSHOT_ONLY,"analysis-source-"+"0".repeat(24)));
            if(defect.equals("WRONG_SCOPE"))planner.factory=(state,context)->AssistantPlan.analysis(new AnalysisDirective("B",REPORT,AnalysisContract.Mode.SNAPSHOT_ONLY,sourceRef(context)));
            var response=chat(key(),input(source.path("conversationId").asText(),"只分析刚才这批原快照，不能查询当前数据。"));
            assertFalse(hasEvent(response,"analysis_task"),defect+": "+response);assertEquals(before,count("business_query_run"));assertEquals(calls,mcpQueries());assertEquals(0,count("business_analysis_run"));
            write(caseFile("snapshot-"+defect.toLowerCase(Locale.ROOT)),Map.of("events",response,"queryCount",calls));
        }
    }

    @Test @Order(13) void ownerPermissionAndKnowledgeChangesHideTaskHistoryAndDetail() throws Exception {
        var doc=publish("销售金额定义","销售金额不等于到账款项。");String key=key();var events=chat(key,input(null,"销售金额和到账有什么区别？"));
        var ref=task(events);String id=ref.path("runId").asText(),conversation=event(events,"conversation").path("conversationId").asText();assertCompleted(id);
        String foreignToken=login(other.userId());
        for(String path:List.of("/api/agent/turns/"+key,"/api/analysis/runs/"+id,"/api/agent/conversations/"+conversation+"/messages"))assertEquals(404,code(http(foreignToken,"GET",path,null,Map.of())),path);
        knowledge.transition(admin,doc.get("id").toString(),new KnowledgeService.Transition(2,"隔离用例撤下答案所依赖资料"),false);
        assertEquals(409,code(http(token,"GET","/api/analysis/runs/"+id,null,Map.of())));assertTrue(receipt(key).path("analysisTask").isNull());assertUnavailableHistory(conversation);
        identities.saveUser(admin,new IdentityStore.UserForm(user.userId(),user.displayName(),null,Set.of("B"),Set.of("report:sales"),false,true));
        String refreshed=login(user.userId());
        for(String path:List.of("/api/agent/turns/"+key,"/api/analysis/runs/"+id,"/api/agent/conversations/"+conversation+"/messages"))assertNotEquals(0,code(http(refreshed,"GET",path,null,Map.of())),path);
    }

    @Test @Order(14) void taskAssociationValidatesChildKeyBothFingerprintsAndCachedCardPayload() throws Exception {
        String key=key();var events=chat(key,input(null,"销售金额是什么？"));var ref=task(events);String id=ref.path("runId").asText(),conversation=event(events,"conversation").path("conversationId").asText();assertCompleted(id);
        var child=jdbc.queryForMap("SELECT request_key,scope_hash,config_hash FROM business_analysis_run WHERE id=?",id);var row=rawReceipt(key);
        jdbc.update("UPDATE business_analysis_run SET request_key=? WHERE id=?","unrelated-child-"+UUID.randomUUID(),id);
        assertTrue(receipt(key).path("analysisTask").isNull());assertUnavailableHistory(conversation);
        jdbc.update("UPDATE business_analysis_run SET request_key=? WHERE id=?",child.get("request_key"),id);
        for(String field:List.of("scope_hash","config_hash")) {
            jdbc.update("UPDATE chat_turn_receipt SET "+field+"=? WHERE request_id=?","0".repeat(64),row.get("request_id"));
            assertTrue(receipt(key).path("analysisTask").isNull());assertUnavailableHistory(conversation);
            jdbc.update("UPDATE chat_turn_receipt SET "+field+"=? WHERE request_id=?",row.get(field),row.get("request_id"));
        }
        var forged=JsonUtil.toMap(ref.toString());forged.put("answer","未经当前校验的伪造缓存结论");
        jdbc.update("UPDATE agent_message SET payload=? WHERE conversation_id=? AND user_id=? AND card_type='analysis_task'",JsonUtil.toJson(forged),conversation,user.userId());
        assertUnavailableHistory(conversation);assertEquals(ref,receipt(key).path("analysisTask"));assertEquals(1,planner.calls.get());
    }

    @Test @Order(15) void deletedConversationMakesIndependentTaskUnreadableAndKeepsReplayTombstone() throws Exception {
        String key=key(),message="销售金额是什么？";var events=chat(key,input(null,message));String id=task(events).path("runId").asText(),conversation=event(events,"conversation").path("conversationId").asText();assertCompleted(id);
        assertEquals(0,code(http(token,"DELETE","/api/agent/conversations/"+conversation,null,Map.of())));
        assertEquals(404,code(http(token,"GET","/api/analysis/runs/"+id,null,Map.of())));assertEquals(404,code(post(key,input(null,message))));assertEquals(1,count("agent_conversation"));
        var retention=agent.getBean(DataRetentionService.class);retention.request(user,conversation,"隔离验收会话删除清理");retention.erase(conversation);
        var tombstone=jdbc.queryForMap("SELECT * FROM chat_turn_receipt WHERE tenant_id=? AND user_id=? AND client_key=?",user.tenantId(),user.userId(),key);
        assertNull(tombstone.get("trusted_request_json"));assertNull(tombstone.get("scope_hash"));assertNull(tombstone.get("config_hash"));assertNull(tombstone.get("analysis_run_id"));
        assertNotNull(tombstone.get("request_hash"));assertEquals(1,count("chat_turn_receipt"));assertEquals(0,count("business_analysis_run"));assertEquals(404,code(post(key,input(null,message))));assertEquals(1,planner.calls.get());
    }

    @Test @Order(16) void expiredReceiptPurgesTrustedBodyButCannotCreateANewConversation() throws Exception {
        String key=ready("销售金额是什么？");var row=rawReceipt(key);jdbc.update("UPDATE chat_turn_receipt SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE request_id=?",row.get("request_id"));
        agent.getBean(DataRetentionService.class).sweep();var skeleton=rawReceipt(key);
        for(String field:List.of("trusted_request_json","scope_hash","config_hash","message","processing_until"))assertNull(skeleton.get(field),field);
        for(String field:List.of("request_id","request_hash","client_key","conversation_id","permission_version"))assertEquals(row.get(field),skeleton.get(field),field);
        assertEquals(410,code(http(token,"GET","/api/agent/turns/"+key,null,Map.of())));assertEquals(410,code(post(key,input(null,"销售金额是什么？"))));
        assertEquals(1,count("agent_conversation"));assertEquals(1,count("chat_turn_receipt"));assertEquals(0,count("business_analysis_run"));assertEquals(1,planner.calls.get());
    }

    @Test @Order(17) void latestEmptyDatabaseMatchesAllDeclaredTableAndColumnComments() throws Exception {
        JsonNode expected=JsonUtil.MAPPER.readTree(readResource("db/schema-comments.json"));
        Set<String> tables=new TreeSet<>();expected.fieldNames().forEachRemaining(tables::add);assertTrue(tables.contains("chat_turn_receipt"));
        var actual=new TreeSet<>(jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME<>'flyway_schema_history'",String.class,SCHEMA));assertEquals(tables,actual);
        int columns=0;for(String table:tables) {
            var spec=expected.path(table);assertEquals(spec.path("comment").asText(),jdbc.queryForObject("SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",String.class,SCHEMA,table));
            var fields=spec.path("columns");columns+=fields.size();for(var names=fields.fieldNames();names.hasNext();) {
                String field=names.next();assertEquals(fields.path(field).asText(),jdbc.queryForObject("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?",String.class,SCHEMA,table,field),table+"."+field);
            }
        }
        assertEquals(columns,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME<>'flyway_schema_history'",Integer.class,SCHEMA));
        write("schema-comments-current.json",Map.of("schema",SCHEMA,"tables",tables,"tableCount",tables.size(),"columnCount",columns,"allCommentsMatched",true));
    }

    @Test @Order(18) void readyRecoveryRejectsCurrentPermissionAndKeepsItsOriginalIdentity() throws Exception {
        String message="销售金额的口径是什么？",key=ready(message);var before=rawReceipt(key);int calls=planner.calls.get();
        identities.saveUser(admin,new IdentityStore.UserForm(user.userId(),user.displayName(),null,Set.of("B"),Set.of("report:sales"),false,true));
        String refreshed=login(user.userId());assertEquals(404,code(http(refreshed,"GET","/api/agent/turns/"+key,null,Map.of())));
        assertEquals(404,code(http(refreshed,"POST","/api/agent/chat",JsonUtil.toJson(input(null,message)),Map.of("Idempotency-Key",key))));
        assertEquals(before,rawReceipt(key));assertEquals(calls,planner.calls.get());assertEquals(0,count("business_analysis_run"));assertEquals(1,count("agent_conversation"));
    }

    @Test @Order(19) void snapshotExpiryAfterReadyCannotChangeModeOrCreateCurrentDataTask() throws Exception {
        var source=queryConversation(normalQuery());useAnalysis(AnalysisContract.Mode.SNAPSHOT_ONLY);analysisModel.plan=dataPlan(true);checkpoint.failure.set("BEFORE_SUBMIT");
        String key=key();var body=input(source.path("conversationId").asText(),"只分析刚才这批快照，按产品统计并展开前两组。");chat(key,body);var before=rawReceipt(key);int calls=planner.calls.get();
        assertEquals("READY",before.get("status"));jdbc.update("UPDATE business_query_run SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",source.path("snapshot").path("id").asText());
        var projected=receipt(key);assertEquals("FAILED",projected.path("status").asText());assertFalse(projected.path("canReconcile").asBoolean());assertEquals(before,rawReceipt(key));
        var result=chat(key,body);assertTrue(hasEvent(result,"error"));assertEquals("FAILED",rawReceipt(key).get("status"));
        assertEquals(before.get("trusted_request_json"),rawReceipt(key).get("trusted_request_json"));assertEquals(calls,planner.calls.get());assertEquals(0,count("business_analysis_run"));assertEquals(1,mcpQueries());
    }

    @Test @Order(20) void temporaryActiveTaskLimitKeepsReadyAndAllowsSameKeyReconciliation() throws Exception {
        var request=new AnalysisContract.Request("A",REPORT,"隔离队列占位",AnalysisContract.Mode.KNOWLEDGE_ONLY,null);
        String first=analyses.submit(user,request,UUID.randomUUID().toString()).get("id").toString();analyses.submit(user,request,UUID.randomUUID().toString());
        String message="销售金额的口径是什么？",key=ready(message);var frozen=rawReceipt(key);int calls=planner.calls.get();
        var busy=chat(key,input(null,message));assertTrue(hasEvent(busy,"error"));assertEquals("READY",receipt(key).path("status").asText());assertTrue(receipt(key).path("canReconcile").asBoolean());
        assertEquals(frozen.get("trusted_request_json"),rawReceipt(key).get("trusted_request_json"));assertEquals(calls,planner.calls.get());assertEquals(2,count("business_analysis_run"));
        analyses.cancel(user,first);var recovered=chat(key,input(null,message));assertEquals("KNOWLEDGE_ONLY",task(recovered).path("mode").asText());assertEquals(3,count("business_analysis_run"));assertEquals(1,cards());assertEquals(calls,planner.calls.get());
    }

    @Test @Order(21) void completedTurnWaitsForMessageProjectionWithoutReplayingThePlanner() throws Exception {projectionRecovery(false);}

    @Test @Order(22) void submittedAnalysisWaitsForCardProjectionWithoutDuplicatingTheTask() throws Exception {projectionRecovery(true);}

    /** A和B代表先后轮次；旧轮次的永久失败只能结束其回执，不能覆盖后来成功查询的语义焦点。 */
    @Test @Order(23) void invalidEarlierReadyCannotPoisonLaterQueryAndItsSnapshotContinuation() throws Exception {
        var document=publish("旧轮次知识依据","销售金额表示订单价款，不代表已收款。");String earlierMessage="销售金额是否等于已收款？";
        String earlierKey=ready(earlierMessage);var earlier=rawReceipt(earlierKey);String conversation=earlier.get("conversation_id").toString();
        knowledge.transition(admin,document.get("id").toString(),new KnowledgeService.Transition(2,"使旧轮次冻结依据失效"),false);
        assertEquals("FAILED",receipt(earlierKey).path("status").asText());assertEquals(earlier,rawReceipt(earlierKey),"GET不能修改旧READY或当前会话状态");
        planner.factory=(state,context)->new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,normalQuery(),false,null);
        var later=chat(key(),input(conversation,"查询A公司当前销售报表，按金额从高到低展示。"));var query=event(later,"business_query");String snapshot=query.path("snapshot").path("id").asText();
        var store=agent.getBean(DialogueStore.class);var before=store.read(user,conversation);int plannerCount=planner.calls.get(),readCount=mcpQueries();
        assertEquals(DialogueState.Phase.READY,before.getPhase());assertEquals("BUSINESS_QUERY",before.getAssistantFocus());assertFalse(before.isUnresolvedRequest());assertFalse(before.isBusinessUnresolved());assertEquals(snapshot,before.getBusinessSnapshotId());
        var stale=chat(earlierKey,input(null,earlierMessage));assertTrue(hasEvent(stale,"error"));assertEquals("FAILED",rawReceipt(earlierKey).get("status"));
        var after=store.read(user,conversation);assertEquals(before.getPhase(),after.getPhase());assertEquals(before.getAssistantFocus(),after.getAssistantFocus());
        assertEquals(before.isUnresolvedRequest(),after.isUnresolvedRequest());assertEquals(before.isBusinessUnresolved(),after.isBusinessUnresolved());
        assertEquals(before.getBusinessQueryRef(),after.getBusinessQueryRef());assertEquals(snapshot,after.getBusinessSnapshotId());assertEquals(JsonUtil.toJson(before),JsonUtil.toJson(after));
        assertEquals(plannerCount,planner.calls.get());assertEquals(readCount,mcpQueries());assertEquals(0,count("business_analysis_run"));
        jdbc.update("UPDATE report_sales SET amount=amount+10000 WHERE tenant_id=? AND company_code='A'",user.tenantId());useAnalysis(AnalysisContract.Mode.SNAPSHOT_ONLY);analysisModel.plan=dataPlan(true);
        var resumed=chat(key(),input(conversation,"只分析刚才这些原快照，按产品汇总金额并展开最高的两组。"));var run=assertCompleted(task(resumed).path("runId").asText());
        assertEquals(snapshot,run.path("request").path("snapshotId").asText());assertEquals(119,step(run,"DRILLDOWN").path("result").path("count").asInt());assertAmountSum(step(run,"GROUP").path("result").path("groups"),"7141.20");
        assertEquals(readCount,mcpQueries());assertEquals(1,count("business_query_run"));assertEquals(1,count("business_analysis_run"));
        write(caseFile("late-ready-preserves-later-query"),Map.of("earlierReceipt",receipt(earlierKey),"laterQuery",query,"stateBeforeLateReconciliation",before,"stateAfterLateReconciliation",after,"continuation",run,"httpMcpBusinessQueries",mcpQueries()));
    }

    /** 真实投影因会话行锁跳过后，回执保留已完成事实；显式读取只反映展示就绪程度，不承担后台补写或重规划。 */
    static void projectionRecovery(boolean analysis) throws Exception {
        if(!analysis)planner.factory=(state,context)->new AssistantPlan(AssistantPlan.Route.HELP,null,false,null);
        projector.delayUser=user.userId();String key=key();var body=input(null,analysis?"销售金额是什么？":"请介绍你能协助的业务功能。");
        try {
            var events=chat(key,body);String conversation=event(events,"conversation").path("conversationId").asText();
            if(analysis)assertCompleted(task(events).path("runId").asText());
            var delayed=receipt(key);assertEquals(analysis?"SUBMITTED":"COMPLETED",delayed.path("status").asText());assertFalse(delayed.path("historyReady").asBoolean(true));
            int calls=planner.calls.get(),analysisCalls=ANALYSIS_CALLS.size();var before=rawReceipt(key);
            int pending=jdbc.queryForObject("SELECT COUNT(*) FROM trace_event WHERE tenant_id=? AND conversation_id=? AND event_type='MESSAGE' AND delivery_status='PENDING'",Integer.class,user.tenantId(),conversation);assertTrue(pending>0);
            assertEquals(0,jdbc.queryForObject("SELECT COALESCE(SUM(delivery_attempts),0) FROM trace_event WHERE tenant_id=? AND conversation_id=? AND event_type='MESSAGE'",Integer.class,user.tenantId(),conversation));
            for(int i=0;i<3;i++)assertEquals(delayed,receipt(key));assertEquals(before,rawReceipt(key));
            var replay=chat(key,body);assertFalse(event(replay,"turn_receipt").path("historyReady").asBoolean(true));assertEquals(calls,planner.calls.get());assertEquals(analysisCalls,ANALYSIS_CALLS.size());
            assertEquals(analysis?1:0,count("business_analysis_run"));assertEquals(0,cards());
            projector.delayUser=null;projector.recover();var recovered=receipt(key);assertTrue(recovered.path("historyReady").asBoolean());
            assertEquals(delayed.path("requestId"),recovered.path("requestId"));assertEquals(delayed.path("analysisTask"),recovered.path("analysisTask"));
            assertEquals(calls,planner.calls.get());assertEquals(analysisCalls,ANALYSIS_CALLS.size());assertEquals(analysis?1:0,count("business_analysis_run"));assertEquals(analysis?1:0,cards());
            var history=http(token,"GET","/api/agent/conversations/"+conversation+"/messages",null,Map.of());assertEquals(0,code(history));
            assertTrue(JsonUtil.MAPPER.readTree(history.body()).path("data").size()>=2);write(caseFile("projection-recovery"),Map.of("delayed",delayed,"recovered",recovered,"pendingMessages",pending,"history",JsonUtil.MAPPER.readTree(history.body())));
        } finally {projector.delayUser=null;projector.recover();}
    }

    @Test @Order(90) @EnabledIfEnvironmentVariable(named="UNIFIED_ANALYSIS_LIVE",matches="true")
    void realModelRoutesUnchangedKnowledgeGoldenQuestionWithoutBusinessRowQueries() throws Exception {
        runKnowledgeGolden("cross-language-no-alias");
    }

    @Test @Order(91) @EnabledIfEnvironmentVariable(named="UNIFIED_ANALYSIS_LIVE",matches="true")
    void realModelKeepsContradictoryKnowledgeSourcesAndNeverQueriesSalesRows() throws Exception {
        runKnowledgeGolden("opposing-tax-policy");
    }

    @Test @Order(92) @EnabledIfEnvironmentVariable(named="UNIFIED_ANALYSIS_LIVE",matches="true")
    void realModelCurrentDataAnalysisPublishesVerifiedGroupDrillAndKnowledge() throws Exception {
        publish("销售口径（隔离验收合成制度）","销售金额以元为单位，币种为CNY；表示销售单的订单金额，不等于已收款。");
        enableLive();int callStart=PLANNER_CALLS.size();
        String question="查询A公司2026年9月的销售报表，按产品汇总销售金额，取金额最高的两个产品，展开它们的全部相关单据，再根据现有业务知识说明销售金额是否等于已收款。";
        var events=chat(key(),input(null,question));var ref=task(events);assertEquals("CURRENT_DATA",ref.path("mode").asText());
        var run=assertCompleted(ref.path("runId").asText());assertEquals(question,run.path("request").path("question").asText());
        assertEquals(120,step(run,"GROUP").path("result").path("count").asInt());assertAmountSum(step(run,"GROUP").path("result").path("groups"),"7141.20");
        assertEquals(119,step(run,"DRILLDOWN").path("result").path("count").asInt());assertFalse(step(run,"KNOWLEDGE").path("result").path("sources").isEmpty());
        assertEquals("ANSWERED",run.path("answer").path("status").asText());assertEquals(1,count("business_query_run"));assertEquals(1,mcpQueries());assertRealPlanningStages(callStart);
        int calls=ANALYSIS_CALLS.size(),drill=step(run,"DRILLDOWN").path("index").asInt();
        var detail=http(token,"GET","/api/analysis/runs/"+ref.path("runId").asText()+"/steps/"+drill+"/rows?page=2",null,Map.of());
        assertEquals(0,code(detail),detail.body());assertEquals(calls,ANALYSIS_CALLS.size());assertEquals(1,mcpQueries());
        write("live-current-data.json",Map.of("events",events,"run",run,"detailPage",JsonUtil.MAPPER.readTree(detail.body()),"httpMcpBusinessQueries",mcpQueries()));
    }

    @Test @Order(93) @EnabledIfEnvironmentVariable(named="UNIFIED_ANALYSIS_LIVE",matches="true")
    void realModelChatQueryAndSnapshotContinuationKeepOriginalIntervalsAndFacts() throws Exception {
        enableLive();int callStart=PLANNER_CALLS.size();
        String queryText="查询A公司2026年9月销售报表，金额大于100元且不超过110元，按金额从高到低展示。";
        var first=chat(key(),input(null,queryText));var query=event(first,"business_query");assertEquals(10,query.path("total").asInt());
        assertEquals("1045.10",query.path("summary").path("amountsByCurrency").path("CNY").asText());
        String conversation=event(first,"conversation").path("conversationId").asText(),snapshot=query.path("snapshot").path("id").asText();assertFalse(snapshot.isBlank());
        // 初查询的草稿和独立期望可能各做一次合法预检；接续边界从已展示的最终查询开始，不能把先前修正次数当作接续补查。
        int initialQueryRuns=count("business_query_run"),initialMcpQueries=mcpQueries();var initialSnapshots=ownSnapshotIds();
        assertTrue(initialQueryRuns>=1);assertTrue(initialMcpQueries>=1);assertTrue(initialSnapshots.contains(snapshot));
        var before=Map.of("recordedAt",Instant.now().toString(),"businessQueryRuns",initialQueryRuns,"httpMcpBusinessQueries",initialMcpQueries,"snapshotIds",initialSnapshots,"acceptedSnapshotId",snapshot);
        write("live-snapshot-query-checkpoint.json",Map.of("beforeSourceChange",before,"queryEvents",first));
        jdbc.update("UPDATE report_sales SET amount=amount+10000,product_name='changed-after-original-query' WHERE tenant_id='T001' AND company_code='A'");
        String question="仅分析刚才这批结果，按产品统计金额合计，取金额最高的一组并展开它的全部单据；不要重新查询当前数据。";
        var second=chat(key(),input(conversation,question));var ref=task(second);assertEquals("SNAPSHOT_ONLY",ref.path("mode").asText());
        var run=assertCompleted(ref.path("runId").asText());assertEquals(snapshot,run.path("request").path("snapshotId").asText());
        var after=Map.of("recordedAt",Instant.now().toString(),"businessQueryRuns",count("business_query_run"),"httpMcpBusinessQueries",mcpQueries(),"snapshotIds",ownSnapshotIds(),"acceptedSnapshotId",snapshot);
        write("live-snapshot-read-boundary.json",Map.of("beforeSourceChange",before,"afterSnapshotAnalysis",after,"analysisRunId",ref.path("runId").asText()));
        assertEquals(query.path("query"),step(run,"SNAPSHOT").path("result").path("query"));assertEquals(10,step(run,"GROUP").path("result").path("count").asInt());
        assertAmountSum(step(run,"GROUP").path("result").path("groups"),"1045.10");assertAmountSum(step(run,"RANK").path("result").path("groups"),"525.05");
        assertEquals(5,step(run,"DRILLDOWN").path("result").path("count").asInt());assertEquals(initialQueryRuns,count("business_query_run"));assertEquals(initialMcpQueries,mcpQueries());assertEquals(initialSnapshots,ownSnapshotIds());assertRealPlanningStages(callStart);
        write("live-snapshot-continuation.json",Map.of("queryEvents",first,"analysisEvents",second,"run",run,"beforeSourceChange",before,"afterSnapshotAnalysis",after));
    }

    /**
     * 单独选取本方法才启动交互夹具；仅提供既有真实接口，父任务负责真实浏览器断言并写明确完成凭据。
     * 控制文件只允许本库固定数据变更、到期和结束，不接收SQL或任意路径；不自动关闭内嵌浏览器。
     */
    @Test @Order(200) @EnabledIfEnvironmentVariable(named="UNIFIED_ANALYSIS_BROWSER",matches="true")
    void realBrowserUnifiedChatFixture() throws Exception {
        var browserKnowledge=publish("销售口径（隔离浏览器合成制度）","销售金额以元为单位，币种为CNY；表示销售单的订单金额，不等于已收款。");
        String browserKnowledgeId=browserKnowledge.get("id").toString();enableLive();
        Path credentials=directory.resolve("browser-credentials.json");
        Files.writeString(credentials,JsonUtil.toJson(Map.of("userId",user.userId(),"password",PASSWORD)),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        write("browser-ready.json",Map.of("schema",SCHEMA,"agentUrl",agentUrl,"userId",user.userId(),"credentialsFile",credentials.toString(),"fullCount",120,"fullAmount","7141.20","knowledgeDocumentId",browserKnowledgeId,"knowledgeVersion",browserKnowledge.get("published_version"),"readyAt",Instant.now().toString()));
        var controls=new ArrayList<Map<String,Object>>();String consumed=null;long until=System.nanoTime()+Duration.ofMinutes(35).toNanos();boolean finished=false;
        while(System.nanoTime()<until) {
            worker.poll();Path control=directory.resolve("browser-control.json");
            if(Files.isRegularFile(control)) {
                var command=JsonUtil.MAPPER.readTree(Files.readString(control));String commandId=command.path("commandId").asText();
                if(!commandId.equals(consumed)) {
                    assertTrue(commandId.matches("[a-zA-Z0-9_-]{8,100}"));assertEquals(SCHEMA,jdbc.queryForObject("SELECT DATABASE()",String.class));
                    String operation=command.path("operation").asText();var details=new LinkedHashMap<String,Object>();
                    switch(operation) {
                        case "CHANGE_CURRENT_SALES" -> jdbc.update("UPDATE report_sales SET amount=amount+10000,product_name='browser-current-source-changed' WHERE tenant_id=? AND company_code='A'",user.tenantId());
                        case "EXPIRE_OWN_SNAPSHOTS" -> jdbc.update("UPDATE business_query_run SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE tenant_id=? AND user_id=?",user.tenantId(),user.userId());
                        case "READY_CHECKPOINT_ONCE" -> checkpoint.failure.set("BEFORE_SUBMIT");
                        case "PUBLISH_KNOWLEDGE_UPDATE" -> {
                            // 只更新本方法创建的固定合成资料；使用真实版本检查及显式发布，不接受控制文件指定正文、范围或对象。
                            var current=knowledge.get(admin,browserKnowledgeId);
                            var saved=knowledge.save(admin,browserKnowledgeId,new KnowledgeService.Change(new KnowledgeService.Content("A",REPORT,
                                    "销售口径（隔离浏览器合成制度）","销售金额以元为单位，币种为CNY，表示销售单的订单价款，不等于已收款；实际到账须另以收款流水证明。这是本轮浏览器版本保护验收发布的更新。",
                                    "本仓库隔离浏览器验收合成资料",null,"2026-01-01",null),((Number)current.get("row_version")).longValue(),"本轮浏览器授权的固定资料更新"));
                            var published=knowledge.transition(admin,browserKnowledgeId,new KnowledgeService.Transition(((Number)saved.get("row_version")).longValue(),"本轮浏览器授权的显式资料发布"),true);
                            details.put("docId",browserKnowledgeId);details.put("rowVersion",published.get("row_version"));details.put("publishedVersion",published.get("published_version"));
                        }
                        case "EVIDENCE_ONLY" -> { }
                        case "COMPLETE" -> {assertTrue(command.path("passed").asBoolean(false),"真实浏览器未通过，不能把结束当作通过");assertFalse(command.path("evidence").isMissingNode());finished=true;}
                        default -> throw new IllegalArgumentException("未声明的隔离浏览器操作");
                    }
                    consumed=commandId;var controlEvent=new LinkedHashMap<String,Object>();controlEvent.put("commandId",commandId);controlEvent.put("operation",operation);controlEvent.put("processedAt",Instant.now().toString());controlEvent.putAll(details);controls.add(controlEvent);
                    var controlResult=new LinkedHashMap<>(controlEvent);controlResult.put("processed",true);
                    String checkpointName="browser-checkpoint-"+commandId+".json";writeNew(checkpointName,browserCheckpoint(commandId,operation));controlResult.put("checkpointFile",checkpointName);
                    write("browser-controls.json",controls);write("browser-control-result.json",controlResult);persistEvidence();
                }
            }
            if(finished)break;Thread.sleep(300);
        }
        assertTrue(finished,"真实浏览器未在夹具期限内明确完成，证据保持但不能计为通过");
    }

    /** 原样读取已版本化的工程金标；评分标签不发给模型，不按本轮输出更改原题或相关来源要求。 */
    static void runKnowledgeGolden(String caseId) throws Exception {
        Path corpusPath=AnalysisKnowledgeIntegrationTest.ragCorpusPath();var corpus=JsonUtil.MAPPER.readTree(Files.readString(corpusPath));
        var documents=new LinkedHashMap<String,String>();for(var item:corpus.path("documents"))documents.put(item.path("id").asText(),publish(item.path("title").asText(),item.path("body").asText()).get("id").toString());
        JsonNode expected=null;for(var item:corpus.path("cases"))if(caseId.equals(item.path("id").asText()))expected=item;assertNotNull(expected);
        String question=expected.path("question").asText();enableLive();int callStart=PLANNER_CALLS.size();
        var evidence=new LinkedHashMap<String,Object>();evidence.put("caseId",caseId);evidence.put("question",question);evidence.put("corpusSha256",Digests.sha256(Files.readString(corpusPath)));evidence.put("passed",false);
        try {
            var events=chat(key(),input(null,question));evidence.put("events",events);var ref=task(events);assertEquals("KNOWLEDGE_ONLY",ref.path("mode").asText());
            var run=assertCompleted(ref.path("runId").asText());evidence.put("run",run);assertEquals(question,run.path("request").path("question").asText());
            assertEquals(expected.path("expectedAnswerStatus").asText(),run.path("answer").path("status").asText());
            Set<String> citations=new LinkedHashSet<>(),returned=new LinkedHashSet<>();
            for(var step:run.path("steps")) {
                assertEquals("KNOWLEDGE",step.path("action").asText());
                for(var source:step.path("result").path("sources"))returned.add(AnalysisKnowledgeIntegrationTest.ragSourceLabel(source.path("originId").asText(),documents));
            }
            for(var claim:run.path("answer").path("claims"))for(var citation:claim.path("citations"))citations.add(AnalysisKnowledgeIntegrationTest.ragSourceLabel(citation.path("sourceId").asText(),documents));
            for(var relevant:expected.path("relevantSourceIds"))assertTrue(returned.contains(relevant.asText()),"知识接续缺少原金标相关来源："+relevant.asText());
            for(var required:expected.path("requiredCitationSourceIds"))assertTrue(citations.contains(required.asText()),"知识答案缺少原金标引用来源："+required.asText());
            assertFalse(citations.isEmpty());assertEquals(0,count("business_query_run"));assertEquals(0,mcpQueries());assertRealPlanningStages(callStart);
            evidence.put("returnedSourceIds",returned);evidence.put("citedSourceIds",citations);evidence.put("passed",true);
        } catch(Exception|AssertionError failure) {evidence.put("error",SensitiveData.text(Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())));throw failure;}
        finally {evidence.put("httpMcpBusinessQueries",mcpQueries());write("live-knowledge-"+caseId+".json",evidence);}
    }

    /** 必须实际出现真实统一规划的目标、草稿和独立复核调用，不能用路由输出或测试替身声称通过。 */
    static void assertRealPlanningStages(int first) {
        assertTrue(PLANNER_CALLS.size()>first);
        for(var call:PLANNER_CALLS.subList(first,PLANNER_CALLS.size())) {
            assertEquals("MODEL",call.get("source"));var diagnostics=JsonUtil.MAPPER.valueToTree(call.get("diagnostics"));
            for(String stage:List.of("PURPOSE","PLAN","REVIEW")) {
                boolean found=false;for(var actual:diagnostics.path("calls"))if(stage.equals(actual.path("stage").asText())&&"RETURNED".equals(actual.path("status").asText()))found=true;
                assertTrue(found,"真实模型缺少已返回的"+stage+"阶段："+diagnostics);
            }
        }
    }

    static void enableLive(){planner.live=true;analysisModel.live=true;}
    static String env(String name,String fallback){return System.getenv().getOrDefault(name,fallback);}
    static String key(){return "chat-"+UUID.randomUUID();}
    static String caseFile(String suffix){return String.format("case-%02d-%s.json",caseNumber,suffix);}
    static void saveDatabases(){write("databases-"+SCHEMA+".json",DATABASES);}
    static synchronized void write(String name,Object value) {
        try {Files.writeString(directory.resolve(name),JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(SensitiveData.value(value)),StandardCharsets.UTF_8);}
        catch(Exception failure){throw new IllegalStateException("隔离验收证据写入失败："+name,failure);}
    }
    /** 不可覆盖的浏览器检查点；同commandId再次执行不得抹掉之前保存的证据。 */
    static synchronized void writeNew(String name,Object value) {
        try {Files.writeString(directory.resolve(name),JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(SensitiveData.value(value)),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
        catch(Exception failure){throw new IllegalStateException("浏览器检查点未保存，原文件不覆盖："+name,failure);}
    }
    /** 固定字段查询当前夹具本人元数据，不包含密码、会话token、原问题或可信分析正文。 */
    static Map<String,Object> browserCheckpoint(String commandId,String operation) {
        var result=new LinkedHashMap<String,Object>();result.put("commandId",commandId);result.put("operation",operation);result.put("recordedAt",Instant.now().toString());result.put("schema",SCHEMA);result.put("userId",user.userId());
        result.put("counts",Map.of("conversations",count("agent_conversation"),"receipts",count("chat_turn_receipt"),"analysisRuns",count("business_analysis_run"),"querySnapshots",count("business_query_run"),"analysisTaskCards",cards(),
                "assistantPlannerCalls",PLANNER_CALLS.stream().filter(call->Integer.valueOf(caseNumber).equals(call.get("case"))).count(),"analysisModelCalls",ANALYSIS_CALLS.stream().filter(call->Integer.valueOf(caseNumber).equals(call.get("case"))).count(),"httpMcpBusinessQueries",mcpQueries()));
        result.put("snapshotIds",ownSnapshotIds());
        result.put("conversations",jdbc.queryForList("SELECT id,status,message_count FROM agent_conversation WHERE tenant_id=? AND user_id=? ORDER BY id LIMIT 200",user.tenantId(),user.userId()));
        result.put("turns",jdbc.queryForList("SELECT request_id,client_key,conversation_id,status,analysis_run_id FROM chat_turn_receipt WHERE tenant_id=? AND user_id=? ORDER BY created_at,request_id LIMIT 200",user.tenantId(),user.userId()));
        result.put("tasks",jdbc.queryForList("SELECT id,request_key,status,stage FROM business_analysis_run WHERE tenant_id=? AND user_id=? ORDER BY created_at,id LIMIT 200",user.tenantId(),user.userId()));
        result.put("metadataLimit",200);result.put("metadataComplete",count("agent_conversation")<=200&&count("chat_turn_receipt")<=200&&count("business_analysis_run")<=200);return result;
    }
    static void persistEvidence() {
        write("planner-evidence.json",PLANNER_CALLS);write("analysis-model-evidence.json",ANALYSIS_CALLS);write("http-mcp-query-evidence.json",HTTP_MCP_QUERIES);
        write("chat-http-evidence.json",HTTP_EVENTS);write("assistant-model-raw-evidence.json",AssistantMcpLiveReviewTest.modelEvidenceSnapshot());
    }
    static String readResource(String name) {
        try(var input=UnifiedAnalysisConversationIntegrationTest.class.getClassLoader().getResourceAsStream(name)){return new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
    /** 空会话、空排除集合和未提供的排除集合保持区别；同键核对直接重用此对象的原始值。 */
    static Map<String,Object> input(String conversation,String message) {
        var input=new LinkedHashMap<String,Object>();input.put("conversationId",conversation);input.put("message",message);input.put("previewId",null);
        input.put("excludedRecords",null);input.put("queryEdit",null);input.put("clarificationReply",null);return input;
    }
    static HttpRequest request(String bearer,String method,String path,String body,Map<String,String> headers) {
        var builder=HttpRequest.newBuilder(URI.create(agentUrl+path)).timeout(Duration.ofSeconds(210)).header("Content-Type","application/json");
        if(bearer!=null)builder.header("Authorization","Bearer "+bearer);headers.forEach(builder::header);
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
    }
    static HttpResponse<String> http(String bearer,String method,String path,String body,Map<String,String> headers) throws Exception {
        return HTTP.send(request(bearer,method,path,body,headers),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    static HttpResponse<String> post(String key,Map<String,Object> body) throws Exception {return http(token,"POST","/api/agent/chat",JsonUtil.toJson(body),Map.of("Idempotency-Key",key));}
    static int code(HttpResponse<String> response) throws Exception {
        var value=JsonUtil.MAPPER.readTree(response.body());return value!=null&&value.has("code")?value.path("code").asInt():response.statusCode();
    }
    static String login(String userId) throws Exception {
        var response=http(null,"POST","/api/auth/login",JsonUtil.toJson(Map.of("userId",userId,"password",PASSWORD)),Map.of());assertEquals(0,code(response),response.body());
        return JsonUtil.MAPPER.readTree(response.body()).path("data").path("token").asText();
    }
    /** 实际读取完整SSE后才解析；任何HTTP失败和重复事件均保留在当前例证据，不只保留最后一帧。 */
    static List<Map<String,Object>> chat(String key,Map<String,Object> body) throws Exception {
        var response=post(key,body);var events=new ArrayList<Map<String,Object>>();
        String kind=null;var data=new StringBuilder();
        for(String line:(response.body()+"\n\n").split("\\R",-1)) {
            if(line.isEmpty()) {if(kind!=null&&data.length()>0)events.add(Map.of("event",kind,"data",JsonUtil.MAPPER.readTree(data.toString())));kind=null;data.setLength(0);}
            else if(line.startsWith("event:"))kind=line.substring(6).stripLeading();
            else if(line.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(line.substring(5).stripLeading());}
        }
        HTTP_EVENTS.add(Map.of("case",caseNumber,"userId",user.userId(),"clientKey",key,"input",body,"httpStatus",response.statusCode(),"events",events,
                "nonSseBody",events.isEmpty()?response.body():"","completedAt",Instant.now().toString()));write("chat-http-evidence.json",HTTP_EVENTS);
        assertEquals(200,response.statusCode(),response.body());assertTrue(hasEvent(events,"done"),response.body());return events;
    }
    static boolean hasEvent(List<Map<String,Object>> events,String kind){return events.stream().anyMatch(event->kind.equals(event.get("event")));}
    static JsonNode event(List<Map<String,Object>> events,String kind) {
        return events.stream().filter(event->kind.equals(event.get("event"))).map(event->JsonUtil.MAPPER.<JsonNode>valueToTree(event.get("data"))).findFirst().orElseThrow(()->new AssertionError("缺少"+kind+"事件："+events));
    }
    static JsonNode task(List<Map<String,Object>> events) {
        var task=event(events,"analysis_task");Set<String> fields=new HashSet<>();task.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("requestId","runId","mode","companyCode","reportId"),fields);return task;
    }
    static JsonNode receipt(String key) throws Exception {
        var response=http(token,"GET","/api/agent/turns/"+key,null,Map.of());assertEquals(0,code(response),response.body());var result=JsonUtil.MAPPER.readTree(response.body()).path("data");assertTrue(result.path("historyReady").isBoolean(),"回执须显式说明历史投影就绪状态");return result;
    }
    /** 数据库原始内容仅用于内部完整性断言；不走HTTP，也不构造对其他用户的读取授权。 */
    static Map<String,Object> rawReceipt(String key){return jdbc.queryForMap("SELECT * FROM chat_turn_receipt WHERE tenant_id=? AND user_id=? AND client_key=?",user.tenantId(),user.userId(),key);}
    static int count(String table) {
        if(!Set.of("agent_conversation","chat_turn_receipt","business_analysis_run","business_query_run").contains(table))throw new IllegalArgumentException("未声明的隔离统计表");
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=? AND user_id=?",Integer.class,user.tenantId(),user.userId());
    }
    static int cards(){return jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE tenant_id=? AND user_id=? AND card_type='analysis_task'",Integer.class,user.tenantId(),user.userId());}
    static int mcpQueries(){return (int)HTTP_MCP_QUERIES.stream().filter(call->user.userId().equals(call.get("userId"))).count();}
    /** 接续前后核对本人的精确快照集合，防止先删后增造成总数不变而掩盖重新读取。 */
    static List<String> ownSnapshotIds(){return jdbc.queryForList("SELECT id FROM business_query_run WHERE tenant_id=? AND user_id=? ORDER BY id",String.class,user.tenantId(),user.userId());}
    static void noBusinessWrites() {
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM report_sales WHERE dispatch_status<>0",Integer.class));
    }
    static Map<String,Object> publish(String title,String body) {
        var document=knowledge.save(admin,null,new KnowledgeService.Change(new KnowledgeService.Content("A",REPORT,title,body,"本仓库隔离验收合成资料",null,"2026-01-01",null),0,"隔离验收创建资料"));
        return knowledge.transition(admin,document.get("id").toString(),new KnowledgeService.Transition(((Number)document.get("row_version")).longValue(),"隔离验收显式发布资料"),true);
    }
    static AnalysisContract.Plan knowledgePlan(String question){return new AnalysisContract.Plan("EXECUTE","核对当前授权知识",null,List.of(new AnalysisContract.Step("KNOWLEDGE",null,null,null,null,null,question)));}
    static BusinessQuery normalQuery(){return new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(REPORT),"A",List.of(),"amount",true,1,20,null);}
    static AnalysisContract.Plan dataPlan(boolean snapshot) {
        return new AnalysisContract.Plan("EXECUTE","按产品汇总并展开排名前两组",null,List.of(
                new AnalysisContract.Step(snapshot?"SNAPSHOT":"QUERY",null,null,snapshot?null:normalQuery(),null,null,null),
                new AnalysisContract.Step("GROUP",0,null,null,new QueryAnalysis.Request(List.of("productName"),null,"NONE",null),null,null),
                new AnalysisContract.Step("RANK",1,null,null,null,new AnalysisContract.Rank("sum",true,2),null),
                new AnalysisContract.Step("DRILLDOWN",0,2,null,null,null,null)));
    }
    static void useAnalysis(AnalysisContract.Mode mode){planner.factory=(state,context)->AssistantPlan.analysis(new AnalysisDirective("A",REPORT,mode,mode==AnalysisContract.Mode.SNAPSHOT_ONLY?sourceRef(context):null));}
    /** 只取本轮外层已经核验的不透明引用；无来源时使用不匹配引用触发生产预检拒绝，绝不改为当前数据。 */
    static String sourceRef(AssistantPlanningContext context) {
        var source=JsonUtil.MAPPER.valueToTree(context.analysisContext()).path("source").path("sourceRef");return source.isTextual()?source.asText():"analysis-source-"+"0".repeat(24);
    }
    static JsonNode queryConversation(BusinessQuery query) throws Exception {
        planner.factory=(state,context)->new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,false,null);
        var events=chat(key(),input(null,"查询A公司销售报表，并保留完整结果。"));
        var result=JsonUtil.MAPPER.createObjectNode();result.setAll((com.fasterxml.jackson.databind.node.ObjectNode)event(events,"business_query"));
        result.put("conversationId",event(events,"conversation").path("conversationId").asText());return result;
    }
    static String ready(String message) throws Exception {
        String key=key();checkpoint.failure.set("BEFORE_SUBMIT");var events=chat(key,input(null,message));assertEquals("READY",event(events,"turn_receipt").path("status").asText());return key;
    }
    /** 工作线程只按显式测试调度运行；最终结果仍经真实HTTP授权入口读取，不能以原始数据库状态代替交付。 */
    static JsonNode awaitRun(String id) throws Exception {
        assertFalse(id.isBlank());worker.poll();long until=System.nanoTime()+Duration.ofSeconds(300).toNanos();
        while(System.nanoTime()<until) {
            String status=jdbc.queryForObject("SELECT status FROM business_analysis_run WHERE id=?",String.class,id);
            if(!Set.of("QUEUED","RUNNING").contains(status)) {
                awaitWorkerIdle();var response=http(token,"GET","/api/analysis/runs/"+id,null,Map.of());assertEquals(0,code(response),response.body());
                var run=JsonUtil.MAPPER.readTree(response.body()).path("data");write(caseFile("run-"+id),run);return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("分析任务未在有界期限内完成："+id);
    }
    static JsonNode assertCompleted(String id) throws Exception {var run=awaitRun(id);assertEquals("COMPLETED",run.path("status").asText(),run.toString());assertFalse(run.path("answer").isNull());return run;}
    static JsonNode step(JsonNode run,String action){for(var step:run.path("steps"))if(action.equals(step.path("action").asText()))return step;throw new AssertionError("缺少"+action+"步骤："+run);}
    static void assertAmountSum(JsonNode groups,String expected){BigDecimal total=BigDecimal.ZERO;for(var group:groups)total=total.add(new BigDecimal(group.path("sum").asText()));assertEquals(0,new BigDecimal(expected).compareTo(total),groups.toString());}
    static void assertUnavailableHistory(String conversation) throws Exception {
        var response=http(token,"GET","/api/agent/conversations/"+conversation+"/messages",null,Map.of());assertEquals(0,code(response),response.body());
        boolean found=false;for(var message:JsonUtil.MAPPER.readTree(response.body()).path("data"))if("analysis_task".equals(message.path("cardType").asText())) {
            found=true;assertTrue(message.path("payload").isEmpty());assertTrue(message.path("content").isNull());assertEquals("UNAVAILABLE",message.path("status").asText());
        }
        assertTrue(found,response.body());assertFalse(response.body().contains("未经当前校验的伪造缓存结论"));
    }
    static void awaitWorkerIdle() throws Exception {
        var executor=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(worker,"workers");
        long until=System.nanoTime()+Duration.ofSeconds(12).toNanos();while(executor.getActiveCount()>0&&System.nanoTime()<until)Thread.sleep(30);
        assertEquals(0,executor.getActiveCount(),"前例工作线程仍在处理，不能复位共享夹具");
    }
    /** 使用真实配额自然到期；既不改限额，也不删除Redis计数来换取通过。 */
    static void respectAnalysisRate() throws Exception {
        var redis=agent.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);String key="quota:analysis:T001:minute:report:"+REPORT;
        long until=System.nanoTime()+Duration.ofSeconds(65).toNanos();while(System.nanoTime()<until){String count=redis.opsForValue().get(key);if(count==null||Long.parseLong(count)<18)return;Thread.sleep(500);}
        throw new AssertionError("报告分析配额没有在自然窗口内恢复，需检查其他并行验收");
    }

    /** 测试专用装配只替换规划输出或故障时序；身份、事务、租约、MCP与分析执行器均保留真实实现。 */
    @TestConfiguration(proxyBeanMethods=false) static class AgentEvidenceConfiguration {
        @Bean @Primary ControlledPlanner controlledPlanner(ModelAssistantPlanner real){return new ControlledPlanner(real);}
        @Bean @Primary EvidenceAnalysisModel evidenceAnalysisModel(AnalysisOpenAiModel real){return new EvidenceAnalysisModel(real);}
        @Bean @Primary BarrierReceiptStore barrierReceiptStore(JdbcTemplate jdbc,TransactionOperations tx,ConversationService conversations,DialogueStore dialogues){return new BarrierReceiptStore(jdbc,tx,conversations,dialogues);}
        @Bean @Primary CheckpointAnalysisService checkpointAnalysisService(AnalysisService analyses,QueryWorkspaceService workspace,ChatTurnReceiptStore receipts,ConversationService conversations){return new CheckpointAnalysisService(analyses,workspace,receipts,conversations);}
        @Bean @Primary DelayedTraceProjector delayedTraceProjector(JdbcTemplate jdbc,DispatchAuditMapper audits,AgentMessageMapper messages,AgentProperties props,PlatformTransactionManager manager){return new DelayedTraceProjector(jdbc,audits,messages,props,manager);}
    }

    /** 在真实MCP工具的业务查询实现外仅计数和取证；不改参数、结果、认证或事务行为。 */
    @TestConfiguration(proxyBeanMethods=false) static class BusinessEvidenceConfiguration {
        @Bean static BeanPostProcessor countHttpMcpQueries() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(!(bean instanceof BusinessReadService))return bean;
                    var proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor)invocation->{
                        if(!"query".equals(invocation.getMethod().getName())||invocation.getArguments().length!=2)return invocation.proceed();
                        var actor=(CurrentUser)invocation.getArguments()[0];var evidence=new LinkedHashMap<String,Object>();long started=System.nanoTime();
                        evidence.put("userId",actor.userId());evidence.put("tenantId",actor.tenantId());evidence.put("query",invocation.getArguments()[1]);evidence.put("transport","HTTP_MCP");
                        try {var result=invocation.proceed();evidence.put("status","RETURNED");evidence.put("result",result);return result;}
                        catch(Throwable failure){evidence.put("status","FAILED");evidence.put("errorType",failure.getClass().getSimpleName());throw failure;}
                        finally {evidence.put("elapsedMs",(System.nanoTime()-started)/1_000_000);evidence.put("completedAt",Instant.now().toString());HTTP_MCP_QUERIES.add(evidence);if(directory!=null)write("http-mcp-query-evidence.json",HTTP_MCP_QUERIES);}
                    });return proxy.getProxy();
                }
            };
        }
    }

    /** 仅由用例显式注入固定计划；真实模式完整委派原规划器，失败不切换测试结果。 */
    static class ControlledPlanner implements AssistantPlanner {
        final ModelAssistantPlanner real;
        final AtomicInteger calls=new AtomicInteger();
        volatile boolean live,block;
        volatile BiFunction<DialogueState,AssistantPlanningContext,AssistantPlan> factory;
        volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ControlledPlanner(ModelAssistantPlanner real){this.real=real;}
        void reset(){live=false;block=false;calls.set(0);entered=new CountDownLatch(1);release=new CountDownLatch(1);factory=null;}
        @Override public IntentParser.Source source(){return live?IntentParser.Source.MODEL:IntentParser.Source.MOCK;}
        @Override public AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies){throw new IllegalStateException("统一验收必须携带真实预检上下文");}
        @Override public AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies,AssistantPlanningContext context) {
            int index=calls.incrementAndGet();boolean actual=live;long start=System.nanoTime();var evidence=new LinkedHashMap<String,Object>();String outcome="FAILED";
            evidence.put("case",caseNumber);evidence.put("source",actual?"MODEL":"TEST_DOUBLE");evidence.put("callWithinCase",index);evidence.put("message",message);evidence.put("analysisContext",context.analysisContext());
            try {
                if(block){entered.countDown();try{if(!release.await(45,TimeUnit.SECONDS))throw new ApiException(503,"隔离规划屏障未按时释放");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ApiException(409,"隔离规划已中断");}}
                AssistantPlan plan;if(actual)plan=real.plan(message,state,reports,companies,context);else{plan=Objects.requireNonNull(factory).apply(state,context);context.validateDraft().apply(plan);}
                evidence.put("plan",plan);outcome="ACCEPTED";return plan;
            } catch(RuntimeException failure){evidence.put("error",SensitiveData.text(failure.getMessage()));throw failure;}
            finally {
                evidence.put("diagnostics",context.diagnostics().snapshot("fixture-observation-"+caseNumber+"-"+index,outcome,Digests.sha256(JsonUtil.toJson(context.dictionary()))));
                evidence.put("outcome",outcome);evidence.put("elapsedMs",(System.nanoTime()-start)/1_000_000);evidence.put("completedAt",Instant.now().toString());PLANNER_CALLS.add(evidence);write("planner-evidence.json",PLANNER_CALLS);
            }
        }
    }

    /**
     * 仅对首次两个非事务RECEIVED读取施加屏障，保证两个真实订阅都经历初始可执行判断。
     * receive内部事务读取不等待，认领事务不等待，避免测试人为持有数据库锁导致假死。
     */
    static class BarrierReceiptStore extends ChatTurnReceiptStore {
        volatile String targetedKey;
        volatile CyclicBarrier barrier=new CyclicBarrier(2);
        final AtomicInteger receivedReaders=new AtomicInteger();
        BarrierReceiptStore(JdbcTemplate jdbc,TransactionOperations tx,ConversationService conversations,DialogueStore dialogues){super(jdbc,tx,conversations,dialogues);}
        void arm(String key){receivedReaders.set(0);barrier=new CyclicBarrier(2);targetedKey=key;}
        void disarm(){targetedKey=null;barrier.reset();}
        /** 仓储可能经过异常翻译代理；通过方法读目标实例的取证计数，不访问代理壳字段。 */
        int receivedReaderCount(){return receivedReaders.get();}
        @Override public Map<String,Object> requireRequest(CurrentUser user,String requestId) {
            var row=super.requireRequest(user,requestId);
            if(Objects.equals(targetedKey,row.get("client_key"))&&!TransactionSynchronizationManager.isActualTransactionActive()&&"RECEIVED".equals(row.get("status"))&&receivedReaders.incrementAndGet()<=2) {
                try{barrier.await(12,TimeUnit.SECONDS);}catch(Exception failure){throw new IllegalStateException("并发RECEIVED屏障没有获得两个订阅",failure);}
            }
            return row;
        }
    }

    /** READY短事务实际提交后才注入一次异常；不直接伪造回执，也不改变重试时使用的可信问题或版本。 */
    static class CheckpointAnalysisService extends ChatAnalysisService {
        final AtomicReference<String> failure=new AtomicReference<>("");
        CheckpointAnalysisService(AnalysisService analyses,QueryWorkspaceService workspace,ChatTurnReceiptStore receipts,ConversationService conversations){super(analyses,workspace,receipts,conversations);}
        @Override public Map<String,Object> submitReady(CurrentUser user,String requestId,DialogueStore.Session session,Runnable guard) {
            if(failure.compareAndSet("BEFORE_SUBMIT",""))throw new ApiException(503,"隔离验收：READY保存后发生一次提交前中断");
            var result=super.submitReady(user,requestId,session,guard);
            if(failure.compareAndSet("AFTER_SUBMIT",""))throw new ApiException(503,"隔离验收：子任务及卡片提交后发生一次响应中断");return result;
        }
    }

    /**
     * 通过独立事务真实锁住会话行，让原投影器执行SKIP LOCKED分支；每次跳过后立即释放，聊天本身不等待长锁。
     * 仅针对当前测试用户开启；关闭后直接调用原deliver/recover完成可靠事件投影，不手工改消息或事件状态。
     */
    static class DelayedTraceProjector extends TraceProjector {
        final JdbcTemplate template;
        final PlatformTransactionManager manager;
        volatile String delayUser;
        DelayedTraceProjector(JdbcTemplate jdbc,DispatchAuditMapper audits,AgentMessageMapper messages,AgentProperties props,PlatformTransactionManager manager){super(jdbc,audits,messages,props,manager);this.template=jdbc;this.manager=manager;}
        @Override public void deliver(long eventId) {
            String actor=delayUser;if(actor==null){super.deliver(eventId);return;}
            var pending=template.queryForList("SELECT tenant_id,conversation_id FROM trace_event WHERE id=? AND user_id=? AND event_type='MESSAGE' AND delivery_status='PENDING'",eventId,actor);
            if(pending.isEmpty()){super.deliver(eventId);return;}var row=pending.get(0);
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
            var lock=executor.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                template.queryForObject("SELECT id FROM agent_conversation WHERE id=? AND tenant_id=? AND user_id=? FOR UPDATE",String.class,row.get("conversation_id"),row.get("tenant_id"),actor);
                locked.countDown();try{if(!release.await(12,TimeUnit.SECONDS))throw new IllegalStateException("投影测试会话行锁未释放");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            }));
            try {
                if(!locked.await(8,TimeUnit.SECONDS))throw new IllegalStateException("隔离投影测试未取得会话行锁");
                super.deliver(eventId);
                if(!"PENDING".equals(template.queryForObject("SELECT delivery_status FROM trace_event WHERE id=?",String.class,eventId)))throw new IllegalStateException("行锁期间原投影器没有跳过事件");
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            finally {release.countDown();try{lock.get(8,TimeUnit.SECONDS);}catch(Exception failure){throw new IllegalStateException("投影测试锁事务未正常结束",failure);}finally{executor.shutdownNow();}}
        }
    }

    /** 分析模型旁路保存阶段和原始响应；程序替身按受控Schema提供数据，真实模型没有重写或失败兜底。 */
    static class EvidenceAnalysisModel implements AnalysisModel {
        final AnalysisOpenAiModel real;
        volatile boolean live;
        volatile String configurationRevision="";
        volatile AnalysisContract.Plan plan;
        EvidenceAnalysisModel(AnalysisOpenAiModel real){this.real=real;}
        @Override public Reply call(String instructions,Object input,String schema,Duration timeout) {
            boolean actual=live;String stage=AnalysisKnowledgeIntegrationTest.FixtureModel.stage(input,schema);long started=System.nanoTime();var row=new LinkedHashMap<String,Object>();
            row.put("case",caseNumber);row.put("source",actual?"MODEL":"TEST_DOUBLE");row.put("stage",stage);row.put("schemaHash",Digests.sha256(schema));row.put("input",input);row.put("status","FAILED");
            try {
                Reply reply;
                if(actual)reply=real.call(instructions,input,schema,timeout);else {
                    Object value;
                    if(AnalysisKnowledgeIntegrationTest.FixtureModel.isPlanSchema(schema))value=plan;
                    else if(schema.equals(AnalysisContract.reviewSchema()))value=new AnalysisContract.Review(true,"状态流转替身仅检查服务端边界，真实模型专项另行验收");
                    else if(stage.startsWith("RAG_"))value=ragReply(stage,input);
                    else {
                        @SuppressWarnings("unchecked") var sources=(List<AnalysisEvidence.Source>)((Map<?,?>)input).get("evidence");
                        value=sources.isEmpty()?new AnalysisEvidence.Draft("INSUFFICIENT",List.of(),List.of("未找到充分依据")):
                                new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("当前结果以所引用的授权依据为准。",List.of(sources.get(0).id()))),List.of());
                    }
                    reply=new Reply(JsonUtil.toJson(value),new ModelUsage(null,null,null),(System.nanoTime()-started)/1_000_000,"ISOLATED_TEST_DOUBLE");
                }
                row.put("rawJson",reply.json());row.put("usage",reply.usage());row.put("model",reply.model());row.put("elapsedMs",reply.elapsedMs());row.put("status","RESPONDED");return reply;
            } catch(RuntimeException failure){row.put("error",SensitiveData.text(failure.getMessage()));row.put("errorType",failure.getClass().getSimpleName());throw failure;}
            finally {row.put("completedAt",Instant.now().toString());ANALYSIS_CALLS.add(row);write("analysis-model-evidence.json",ANALYSIS_CALLS);}
        }
        @Override public Map<String,Object> configuration(){var current=new LinkedHashMap<>(real.configuration());if(!configurationRevision.isEmpty())current.put("isolatedTestRevision",configurationRevision);return current;}
        /** 不依据题目挑选答案；改写不添加词语、排序回显有限候选，供权限和恢复边界测试使用。 */
        static Map<String,Object> ragReply(String stage,Object input) {
            var values=JsonUtil.MAPPER.valueToTree(input);var topics=new ArrayList<Map<String,Object>>();
            for(var topic:values.path("topics")) {
                var item=new LinkedHashMap<String,Object>();item.put("index",topic.path("index").asInt());
                if(stage.equals("RAG_REWRITE")){item.put("firstQuery",null);item.put("secondQuery",null);}else{var ids=new ArrayList<String>();topic.path("sourceIds").forEach(id->ids.add(id.asText()));item.put("sourceIds",ids);}
                topics.add(item);
            }
            return Map.of("topics",topics);
        }
    }
}
