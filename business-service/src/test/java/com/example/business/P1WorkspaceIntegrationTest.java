package com.example.business;

import com.example.report.ReportApplication;
import com.example.report.assistant.*;
import com.example.report.common.*;
import com.example.report.dispatch.*;
import com.example.report.mcp.BusinessMcpClient;
import com.example.report.operations.SensitiveData;
import com.example.report.permission.*;
import com.example.report.security.IdentityStore;
import com.example.report.workspace.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P1专用隔离库验收：真实MySQL、Redis、认证HTTP MCP及Agent接口；真实模型与浏览器分别显式开启。
 * 先记录已有数据库并确认新名不存在，所有写入和删除限定本类创建的UUID库；不修改report_demo。
 */
@EnabledIfEnvironmentVariable(named="P1_WORKSPACE",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class P1WorkspaceIntegrationTest {
    static final String SCHEMA="p1_workspace_it_"+UUID.randomUUID().toString().replace("-","");
    static final String REPORT="rpt-sales-order",PASSWORD=UUID.randomUUID().toString(),SECRET=UUID.randomUUID()+"-"+UUID.randomUUID();
    static final List<Map<String,Object>> CASES=new ArrayList<>(),DATABASES=new ArrayList<>();
    static ServletWebServerApplicationContext business,agent;
    static JdbcTemplate master,jdbc;
    static BusinessMcpClient mcp;
    static IdentityStore identities;
    static CurrentUser admin,user;
    static QueryWorkspaceService workspace;
    static QuerySnapshotStore snapshots;
    static QueryExportService exports;
    static TaskInboxService inbox;
    static Path dir;
    static String agentUrl,businessUrl;
    static boolean created;
    static List<String> baseline;
    static String env(String key,String fallback){return System.getenv().getOrDefault(key,fallback);}
    @RegisterExtension TestWatcher evidence=new TestWatcher(){
        @Override public void testSuccessful(ExtensionContext context){record(context,true,null);}
        @Override public void testFailed(ExtensionContext context,Throwable cause){record(context,false,Objects.toString(cause.getMessage(),cause.getClass().getSimpleName()));}
        private void record(ExtensionContext context,boolean pass,String error){var result=new LinkedHashMap<String,Object>();result.put("test",context.getDisplayName());result.put("passed",pass);result.put("error",SensitiveData.text(error));CASES.add(result);write("integration-results.json",Map.of("schema",SCHEMA,"cases",CASES,"recordedAt",Instant.now().toString()));}
    };
    @BeforeAll static void start() throws Exception {
        dir=Path.of(System.getenv("P1_WORKSPACE_DIR")).toAbsolutePath().normalize();assertTrue(Files.isDirectory(dir));
        String base="jdbc:mysql://"+env("DB_HOST","127.0.0.1")+":"+env("DB_PORT","3306")+"/";
        master=new JdbcTemplate(new DriverManagerDataSource(base+"?serverTimezone=Asia/Shanghai",env("DB_USERNAME","root"),env("DB_PASSWORD","")));
        baseline=master.queryForList("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME",String.class);assertFalse(baseline.contains(SCHEMA));
        assertTrue(SCHEMA.matches("p1_workspace_it_[a-f0-9]{32}"));
        DATABASES.add(Map.of("event","before","existing",baseline,"newSchema",SCHEMA,"time",Instant.now().toString()));write("databases-"+SCHEMA+".json",DATABASES);
        master.execute("CREATE DATABASE `"+SCHEMA+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");created=true;
        DATABASES.add(Map.of("event","created","schema",SCHEMA,"time",Instant.now().toString()));write("databases-"+SCHEMA+".json",DATABASES);
        var common=new ArrayList<>(List.of("--server.port=0","--spring.datasource.url="+base+SCHEMA+"?serverTimezone=Asia/Shanghai",
                "--spring.datasource.username="+env("DB_USERNAME","root"),"--spring.datasource.password="+env("DB_PASSWORD",""),
                "--spring.data.redis.host="+env("REDIS_HOST","127.0.0.1"),"--spring.data.redis.port="+env("REDIS_PORT","6379"),"--spring.data.redis.password="+env("REDIS_PASSWORD",""),
                "--business.service-token="+SECRET,"--security.bootstrap-password="+PASSWORD,"--security.enabled=true","--logging.level.root=WARN"));
        business=(ServletWebServerApplicationContext)new SpringApplicationBuilder(BusinessApplication.class)
                .properties("spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.model.image=none","spring.ai.model.moderation=none","spring.ai.model.audio.speech=none","spring.ai.model.audio.transcription=none")
                .run(common.toArray(String[]::new));
        jdbc=business.getBean(JdbcTemplate.class);assertEquals(SCHEMA,jdbc.queryForObject("SELECT DATABASE()",String.class));
        identities=business.getBean(IdentityStore.class);admin=identities.resolve("T001","admin");
        identities.saveUser(admin,new IdentityStore.UserForm("p1reader","P1 验收用户",PASSWORD,Set.of("A"),Set.of("report:sales"),false,true));
        identities.saveUser(admin,new IdentityStore.UserForm("p1other","P1 另一用户",PASSWORD,Set.of("B"),Set.of("report:sales"),false,true));user=identities.resolve("T001","p1reader");
        businessUrl="http://127.0.0.1:"+business.getWebServer().getPort();mcp=new BusinessMcpClient(JsonUtil.MAPPER,businessUrl,SECRET);
        common.removeIf(arg->arg.startsWith("--server.port="));common.add("--server.port="+env("P1_AGENT_PORT","0"));
        common.addAll(List.of("--spring.flyway.enabled=false","--business.remote.enabled=true","--business.remote.url="+businessUrl,
                "--agent.llm.mock=false","--agent.semantic.mode=active","--agent.semantic.native-schema=true","--agent.semantic.thinking-enabled=false",
                "--agent.investigation.model="+System.getenv("LLM_MODEL"),"--agent.investigation.native-schema=true","--agent.investigation.thinking-enabled=false",
                "--spring.ai.openai.api-key="+System.getenv("LLM_API_KEY"),"--spring.ai.openai.base-url="+System.getenv("LLM_BASE_URL"),"--spring.ai.openai.chat.options.model="+System.getenv("LLM_MODEL"),"--agent.semantic.model="+System.getenv("LLM_MODEL"),
                "--agent.query-export-initial-ms=3600000","--agent.query-export-poll-ms=3600000"));
        AssistantMcpLiveReviewTest.clearModelEvidence();
        agent=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ReportApplication.class,AssistantMcpLiveReviewTest.ModelEvidence.class).profiles("real","mcp").run(common.toArray(String[]::new));
        agentUrl="http://127.0.0.1:"+agent.getWebServer().getPort();workspace=agent.getBean(QueryWorkspaceService.class);snapshots=agent.getBean(QuerySnapshotStore.class);exports=agent.getBean(QueryExportService.class);inbox=agent.getBean(TaskInboxService.class);
    }
    @AfterAll static void stop() throws Exception {
        if(mcp!=null)mcp.close();if(agent!=null)agent.close();if(business!=null)business.close();
        if(created){assertTrue(SCHEMA.matches("p1_workspace_it_[a-f0-9]{32}"));assertFalse(baseline.contains(SCHEMA));
            assertEquals(1,master.queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?",Integer.class,SCHEMA));master.execute("DROP DATABASE `"+SCHEMA+"`");
            var after=master.queryForList("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME",String.class);assertFalse(after.contains(SCHEMA));assertTrue(after.containsAll(baseline));DATABASES.add(Map.of("event","removed","schema",SCHEMA,"preexistingPreserved",true,"remaining",after,"time",Instant.now().toString()));write("databases-"+SCHEMA+".json",DATABASES);}
        if(dir!=null)write("model-evidence.json",AssistantMcpLiveReviewTest.modelEvidenceSnapshot());
    }
    @BeforeEach void reset() {
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT DATABASE()",String.class));
        for(String table:List.of("task_inbox_read","query_export_job","saved_business_query","business_query_run","dispatch_job","business_dispatch_request","dispatch_plan_item","dispatch_plan","dispatch_preview_item","dispatch_preview"))jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE app_user SET companies_json='[\"A\"]',permissions_json='[\"report:sales\"]',enabled=1 WHERE tenant_id='T001' AND user_id='p1reader'");
        jdbc.update("UPDATE report_definition SET catalog_version=1 WHERE tenant_id='T001' AND report_id=?",REPORT);
        jdbc.update("DELETE FROM report_sales");
        var seed=new ArrayList<Object[]>();for(int i=0;i<120;i++)seed.add(new Object[]{1000+i,"T001","A","P1-"+i,i==1?"=1+1":i%2==0?"Alpha":"alpha ",new BigDecimal(i+".01"),java.sql.Date.valueOf("2026-09-"+String.format("%02d",1+i%28)),0});
        seed.add(new Object[]{2000,"T001","B","P1-B","越权记录",new BigDecimal("999999"),java.sql.Date.valueOf("2026-09-01"),0});
        seed.add(new Object[]{3000,"T_OTHER","A","P1-TENANT","其他租户",new BigDecimal("999999"),java.sql.Date.valueOf("2026-09-01"),0});
        jdbc.batchUpdate("INSERT INTO report_sales(id,tenant_id,company_code,order_no,product_name,amount,sale_date,dispatch_status) VALUES (?,?,?,?,?,?,?,?)",seed);
        user=identities.resolve("T001","p1reader");
    }
    static BusinessQuery query(List<BusinessQuery.Group> conditions){return new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(REPORT),"A",conditions,"amount",true,1,20,null);}
    static BusinessQuery.Filter f(String name,String op,String...v){return new BusinessQuery.Filter(name,op,List.of(v));}
    static BusinessQuery.Group g(BusinessQuery.Filter...conditions){return new BusinessQuery.Group(List.of(conditions));}
    static BusinessResult query(BusinessQuery q){return mcp.call("business_query",user,Map.of("query",q),new TypeReference<>(){});}
    @Test @Order(1) void immutablePagesKeepOriginalRowsTotalsAndStrictOwnership() {
        var result=query(query(List.of()));assertEquals(120,result.total());assertNotNull(result.snapshot());assertEquals(120,result.snapshot().scannedRows());
        var run=workspace.require(user,result.snapshot().id());var all=snapshots.all(run);assertEquals(120,all.size());assertEquals("1119",all.get(0).get("recordId"));
        var second=workspace.page(user,result.snapshot().id(),2);assertEquals("1099",second.rows().get(0).get("recordId"));
        jdbc.update("UPDATE report_sales SET amount=90071992547409.93,product_name='changed' WHERE id=1099");
        assertEquals(second,workspace.page(user,result.snapshot().id(),2));
        var later=query(query(List.of()));assertEquals("1099",later.rows().get(0).get("recordId"));assertEquals("90071992547409.93",later.rows().get(0).get("amount"));
        assertThrows(ApiException.class,()->workspace.page(identities.resolve("T001","p1other"),result.snapshot().id(),1));
        jdbc.update("UPDATE app_user SET companies_json='[\"B\"]' WHERE user_id='p1reader'");assertThrows(ApiException.class,()->workspace.page(user,result.snapshot().id(),1));
    }
    @Test @Order(2) void sqlPushdownMatchesIndependentCountsAndFullScanSemantics() {
        var all=query(query(List.of()));var complete=snapshots.all(workspace.require(user,all.snapshot().id()));
        var cases=List.of(List.of(g(f("amount","GT","100"))),List.of(g(f("amount","GTE","100.01"),f("amount","LTE","110.01"))),
                List.of(g(f("productName","EQ","Alpha"))),List.of(g(f("productName","EQ","alpha "))),List.of(g(f("productName","NE","Alpha"))),
                List.of(g(f("productName","CONTAINS","=1+1"))),List.of(g(f("productName","CONTAINS","%_"))),
                List.of(g(f("date","GTE","2026-09-10"),f("date","LTE","2026-09-20"))),
                List.of(g(f("amount","GT","110")),g(f("productName","EQ","=1+1"))),
                List.of(g(f("amount","GT","110")),g(f("dispatched","EQ","false"))),List.of(g(f("amount","IS_NULL"))),List.of(g(f("amount","NOT_NULL"))));
        var evidence=new ArrayList<Map<String,Object>>();
        for(var conditions:cases){var q=query(conditions);var expected=BusinessQueryEngine.materialize(q,all.columns(),complete,all.source());var actual=query(q);assertEquals(expected.rows(),snapshots.all(workspace.require(user,actual.snapshot().id())));assertEquals(expected.result().summary(),actual.summary());evidence.add(Map.of("conditions",conditions,"total",actual.total(),"scanned",actual.snapshot().scannedRows()));}
        assertEquals(20,query(query(List.of(g(f("amount","GT","100"))))).snapshot().scannedRows());assertEquals(60,query(query(List.of(g(f("productName","EQ","Alpha"))))).total());
        write("pushdown-equivalence.json",Map.of("passed",true,"totalSourceRows",120,"cases",evidence,"independentSqlCounts",Map.of("amountOver100",20,"caseSensitiveAlpha",60)));
    }
    @Test @Order(3) void savedQueriesReauthorizeVersionAndComparisonRevealsScopeChanges() {
        var first=query(query(List.of()));var saved=workspace.save(user,first.snapshot().id(),new QueryWorkspaceService.Save("每月销售","LAST_MONTH","date"));
        var rerun=workspace.runSaved(user,saved.get("id").toString());assertEquals(120,rerun.total());assertEquals(2,rerun.query().conditions().get(0).allOf().size());
        var diff=workspace.compare(user,first.snapshot().id(),rerun.snapshot().id());assertEquals(false,diff.get("sameDefinition"));assertEquals(0,diff.get("added"));
        workspace.updateSaved(user,saved.get("id").toString(),1,new QueryWorkspaceService.Save("每月销售已命名","LAST_MONTH","date"));assertThrows(ApiException.class,()->workspace.deleteSaved(user,saved.get("id").toString(),1));
        jdbc.update("UPDATE report_definition SET catalog_version=catalog_version+1 WHERE report_id=?",REPORT);assertThrows(ApiException.class,()->workspace.runSaved(user,saved.get("id").toString()));
    }
    @Test @Order(4) void exportIsIdempotentExactAndCurrentPermissionProtected() throws Exception {
        jdbc.update("UPDATE report_sales SET amount=90071992547409.93 WHERE id=1000");var r=query(query(List.of()));String key=UUID.randomUUID().toString();
        var job=exports.submit(user,r.snapshot().id(),key);assertEquals(job.id(),exports.submit(user,r.snapshot().id(),key).id());
        var other=query(query(List.of(g(f("amount","GT","100")))));assertThrows(ApiException.class,()->exports.submit(user,other.snapshot().id(),key));
        exports.poll();var done=exports.get(user,job.id());assertEquals("SUCCEEDED",done.status(),done.message());assertEquals(120,done.progressRows());
        byte[] file=exports.download(user,job.id());String text=new String(file,java.nio.charset.StandardCharsets.UTF_8);assertTrue(text.contains("'90071992547409.93"));assertTrue(text.contains("'=1+1"));assertFalse(text.contains("P1-B"));assertFalse(text.contains("P1-TENANT"));
        assertThrows(ApiException.class,()->exports.download(identities.resolve("T001","p1other"),job.id()));
        jdbc.update("UPDATE app_user SET companies_json='[\"B\"]' WHERE user_id='p1reader'");assertThrows(ApiException.class,()->exports.download(user,job.id()));
        write("export-evidence.json",Map.of("job",done,"bytes",file.length,"passed",true,"csvFormulaAndPrecision",true));
    }
    @Test @Order(5) void cancelledAndExpiredLeaseWorkersCannotCommitAndCanRecover() {
        var r=query(query(List.of()));var job=exports.submit(user,r.snapshot().id(),UUID.randomUUID().toString());
        Map<String,Object> claim=ReflectionTestUtils.invokeMethod(exports,"claim");assertNotNull(claim);exports.cancel(user,job.id());ReflectionTestUtils.invokeMethod(exports,"generate",claim);assertEquals("CANCELLED",exports.get(user,job.id()).status());assertThrows(ApiException.class,()->exports.download(user,job.id()));
        var retry=exports.submit(user,r.snapshot().id(),UUID.randomUUID().toString());Map<String,Object> old=ReflectionTestUtils.invokeMethod(exports,"claim");
        jdbc.update("UPDATE query_export_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",retry.id());ReflectionTestUtils.invokeMethod(exports,"recover");
        Map<String,Object> current=ReflectionTestUtils.invokeMethod(exports,"claim");assertNotEquals(old.get("claim_token"),current.get("claim_token"));ReflectionTestUtils.invokeMethod(exports,"generate",old);assertEquals("RUNNING",exports.get(user,retry.id()).status());ReflectionTestUtils.invokeMethod(exports,"generate",current);assertEquals("SUCCEEDED",exports.get(user,retry.id()).status());
        workspace.expire(user,r.snapshot().id());assertThrows(ApiException.class,()->exports.download(user,retry.id()));exports.cleanup();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_query_row",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM query_export_job WHERE content IS NOT NULL",Integer.class));
    }
    @Test @Order(6) void notificationsDeduplicateStatesAndRecheckOwnership() {
        var r=query(query(List.of()));var job=exports.submit(user,r.snapshot().id(),UUID.randomUUID().toString());exports.poll();
        var page=inbox.list(user,"EXPORT",true,null);assertEquals(1,page.rows().size());var item=page.rows().get(0);assertEquals(job.id(),item.id());assertTrue(item.unread());
        inbox.read(user,item.kind(),item.id(),item.eventKey());inbox.read(user,item.kind(),item.id(),item.eventKey());assertEquals(0,inbox.list(user,"EXPORT",true,null).rows().size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM task_inbox_read",Integer.class));
        assertEquals(0,inbox.list(identities.resolve("T001","p1other"),"ALL",false,null).rows().size());assertThrows(ApiException.class,()->inbox.read(user,item.kind(),item.id(),"old-state"));
        jdbc.update("UPDATE app_user SET companies_json='[\"B\"]' WHERE user_id='p1reader'");assertThrows(ApiException.class,()->inbox.read(user,item.kind(),item.id(),item.eventKey()));
    }
    @Test @Order(7) void rankedPlansUseFullQueryRemainPendingAndRejectChangedRanking() {
        var r=query(query(List.of()));var ranking=new QueryAnalysis.Rank("amount",true,3);assertEquals(List.of("1119","1118","1117"),workspace.rank(user,r.snapshot().id(),ranking).stream().map(row->row.get("recordId")).toList());
        var plan=workspace.prepareRank(user,r.snapshot().id(),ranking);assertEquals("PENDING",plan.status());assertEquals(3,plan.count());assertEquals(plan.planId(),workspace.prepareRank(user,r.snapshot().id(),ranking).planId());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
        var next=query(query(List.of()));jdbc.update("UPDATE report_sales SET amount=99999 WHERE id=1000");assertThrows(ApiException.class,()->workspace.prepareRank(user,next.snapshot().id(),ranking));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan",Integer.class));
        var response=agent.getBean(DispatchService.class).cancel(user,plan.planId());assertEquals("CANCELLED",response.getStatus());
    }
    @Test @Order(8) void authenticatedHttpUsesStrictFormsAndNeverExportsOtherUsersData() throws Exception {
        var r=query(query(List.of()));String token=login();
        var page=api(token,"GET","/api/query-workspace/runs/"+r.snapshot().id()+"?page=2",null);assertEquals(0,page.path("code").asInt());assertEquals(20,page.path("data").path("rows").size());
        var invalid=api(token,"POST","/api/query-workspace/runs/"+r.snapshot().id()+"/rank",Map.of("field","amount","descending",true,"count",1.5));assertEquals(422,invalid.path("code").asInt());
        var unknown=api(token,"POST","/api/query-workspace/runs/"+r.snapshot().id()+"/rank",Map.of("field","amount","descending",true,"count",3,"tenantId","OTHER"));assertEquals(422,unknown.path("code").asInt());
        var invalidNotice=new LinkedHashMap<String,Object>();invalidNotice.put("kind",null);invalidNotice.put("id","bad");invalidNotice.put("eventKey","bad");
        assertEquals(422,api(token,"POST","/api/query-workspace/inbox/read",invalidNotice).path("code").asInt());
        var denied=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(agentUrl+"/api/query-workspace/runs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,denied.statusCode());
    }
    /** 独立缺行故障同时覆盖展示页和跨500行计算批次，不能用下一批记录补齐缺口。 */
    @Test @Order(9) void missingSnapshotSequencesRejectPagesAnalysisAndExports() {
        addSales(120,700);var result=query(query(List.of()));assertEquals(700,result.total());String id=result.snapshot().id();
        jdbc.update("DELETE FROM business_query_row WHERE run_id=? AND seq=10",id);
        assertThrows(ApiException.class,()->workspace.page(user,id,1));
        assertThrows(ApiException.class,()->snapshots.all(workspace.require(user,id)));
        assertThrows(ApiException.class,()->workspace.analyze(user,id,new QueryAnalysis.Request(List.of(),null,"NONE",null)));
        var job=exports.submit(user,id,UUID.randomUUID().toString());exports.poll();assertEquals("FAILED",exports.get(user,job.id()).status());assertThrows(ApiException.class,()->exports.download(user,job.id()));
        write("missing-sequence-evidence.json",Map.of("sourceRows",700,"missingSequence",10,"pageRejected",true,"analysisRejected",true,"exportStatus","FAILED"));
    }
    @Test @Order(10) void allVisibleReportRequestsPersistTheirResolvedDefinition() {
        var request=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(),"A",List.of(),"amount",true,1,20,null);
        var result=query(request);assertEquals(List.of(REPORT),result.query().reportIds());assertEquals(120,workspace.page(user,result.snapshot().id(),2).total());
    }
    /** 固定规模测量只说明本机测试集的读取与下推效果，不作为生产容量或延迟承诺。 */
    @Test @Order(11) void fixedScalePushdownMeasuresRowsAndKeepsExactResults() {
        addSales(120,5120);long start=System.nanoTime();var full=query(query(List.of()));long fullMs=(System.nanoTime()-start)/1_000_000;
        start=System.nanoTime();var filtered=query(query(List.of(g(f("amount","GT","5070")))));long filteredMs=(System.nanoTime()-start)/1_000_000;
        assertEquals(5120,full.total());assertEquals(50,filtered.total());assertEquals(50,filtered.snapshot().scannedRows());
        var expected=BusinessQueryEngine.materialize(filtered.query(),full.columns(),snapshots.all(workspace.require(user,full.snapshot().id())),full.source());
        assertEquals(expected.rows(),snapshots.all(workspace.require(user,filtered.snapshot().id())));assertEquals("254725.50",filtered.summary().amountsByCurrency().get("CNY"));
        var explain=jdbc.queryForList("EXPLAIN SELECT id FROM report_sales WHERE tenant_id='T001' AND company_code='A' AND amount>5070 ORDER BY id LIMIT 100");
        write("fixed-scale-evidence.json",Map.of("sourceRows",5120,"matchingRows",50,"rowsBeforeCommonPredicate",filtered.snapshot().scannedRows(),"fullHttpMillis",fullMs,"filteredHttpMillis",filteredMs,"exactAmount","254725.50","explain",explain,"scope","本机合成数据与真实HTTP MCP；非生产容量验收"));
    }
    /** 真实预览后修改本夹具来源，再由原执行器复核为跳过；验证异常提醒来自实际状态流转。 */
    @Test @Order(12) void changedSourceProducesActionableNoticeWithoutAnUnauthorizedWrite() {
        var result=query(query(List.of()));var plan=workspace.prepareRank(user,result.snapshot().id(),new QueryAnalysis.Rank("amount",true,1));
        var pending=inbox.list(user,"PLAN",false,null);assertEquals(1,pending.rows().size());assertEquals(plan.planId(),pending.rows().get(0).target());
        jdbc.update("UPDATE report_sales SET amount=0 WHERE tenant_id='T001' AND id=1119");
        agent.getBean(DispatchService.class).confirm(user,plan.planId());
        assertEquals("SKIPPED",jdbc.queryForObject("SELECT status FROM dispatch_plan_item WHERE plan_id=?",String.class,plan.planId()));
        assertEquals(0,inbox.list(user,"PLAN",false,null).rows().size());assertEquals(1,inbox.list(user,"ATTENTION",false,null).rows().size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
    }
    @Test @Order(13) void workOrderSnapshotsRetainSourceBoundariesAndCannotPrepareDispatch() {
        var request=new BusinessQuery(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.LIST,List.of(REPORT),"A",List.of(),null,false,1,20,null);
        var result=query(request);assertTrue(result.total()>0);assertEquals("SOURCE_OBSERVATION",result.snapshot().consistency());
        assertEquals(result.rows(),workspace.page(user,result.snapshot().id(),1).rows());assertTrue(result.rows().get(0).containsKey("steps"));
        assertThrows(ApiException.class,()->workspace.prepareRank(user,result.snapshot().id(),new QueryAnalysis.Rank("amount",true,1)));
    }
    /** 在同一已验证隔离库中补充确定性来源数据；序号区间不含B公司或其他租户夹具。 */
    static void addSales(int from,int until) {
        var rows=new ArrayList<Object[]>();for(int i=from;i<until;i++)rows.add(new Object[]{10000+i,"T001","A","P1-scale-"+i,"规模测量",new BigDecimal(i+".01"),java.sql.Date.valueOf("2026-09-01"),0});
        jdbc.batchUpdate("INSERT INTO report_sales(id,tenant_id,company_code,order_no,product_name,amount,sale_date,dispatch_status) VALUES (?,?,?,?,?,?,?,?)",rows);
    }
    @Test @Order(90) @EnabledIfEnvironmentVariable(named="P1_LIVE",matches="true") void realModelQueryAndCorrectionKeepStructuredPagingModelFree() throws Exception {
        String token=login();var evidence=new ArrayList<Map<String,Object>>();String conversation=null;
        for(String message:List.of("查询A公司销售报表，金额大于100元的记录，按金额从大到小排列，每页5条。","金额下限改成110元以上，其他条件不变。")) {
            var input=new LinkedHashMap<String,Object>();input.put("message",message);input.put("conversationId",conversation);String clientKey="chat-"+UUID.randomUUID();
            var response=http(token,"POST","/api/agent/chat",input,Map.of("Idempotency-Key",clientKey));var events=events(response.body());
            var item=new LinkedHashMap<String,Object>();item.put("message",message);item.put("clientKey",clientKey);item.put("events",events);evidence.add(item);write("live-evidence.json",evidence);
            assertTrue(events.containsKey("business_query"),response.body());assertTrue(events.containsKey("done"));var result=events.get("business_query");conversation=events.get("conversation").path("conversationId").asText();
            assertEquals(message.startsWith("查询")?20:10,result.path("total").asInt());int calls=AssistantMcpLiveReviewTest.modelEvidenceSnapshot().size();
            String run=result.path("snapshot").path("id").asText();var page=api(token,"GET","/api/query-workspace/runs/"+run+"?page=2",null);assertEquals(0,page.path("code").asInt());assertEquals(5,page.path("data").path("rows").size());assertEquals(calls,AssistantMcpLiveReviewTest.modelEvidenceSnapshot().size());
            item.put("pagingAddsModelCalls",false);item.put("passed",true);write("live-evidence.json",evidence);
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan",Integer.class));
    }
    @Test @Order(100) @EnabledIfEnvironmentVariable(named="P1_BROWSER",matches="true") void browserFixtureWaitsForRealUiAcceptance() throws Exception {
        Path ready=dir.resolve("browser-ready.json"),command=dir.resolve("browser-command.json");
        if("true".equals(System.getenv("P1_BROWSER_CHANGED"))) {
            var result=query(query(List.of()));var plan=workspace.prepareRank(user,result.snapshot().id(),new QueryAnalysis.Rank("amount",true,1));
            jdbc.update("UPDATE report_sales SET amount=0 WHERE tenant_id='T001' AND id=1119");
            var orders=query(new BusinessQuery(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.LIST,List.of(REPORT),"A",List.of(),null,false,1,20,null));
            write("browser-scenario.json",Map.of("kind","SOURCE_CHANGED_AFTER_PREPARE","planId",plan.planId(),"runId",result.snapshot().id(),"recordId","1119","docNo","P1-119","beforeAmount","119.01","currentAmount","0.00","expectedResult","SKIPPED","businessWritesBeforeConfirm",0,"workOrderRunId",orders.snapshot().id()));
        }
        Files.deleteIfExists(command);Files.writeString(dir.resolve("browser-credentials.json"),JsonUtil.toJson(Map.of("userId","p1reader","password",PASSWORD)));
        write("browser-ready.json",Map.of("agentUrl",agentUrl,"businessUrl",businessUrl,"schema",SCHEMA,"model",System.getenv("LLM_MODEL"),"profiles",List.of("real","mcp"),"semanticMode","active"));
        long until=System.nanoTime()+Duration.ofMinutes(120).toNanos();boolean done=false;var handled=new HashSet<String>();
        while(System.nanoTime()<until && !done) {
            exports.poll();
            if(Files.exists(command)){var value=JsonUtil.MAPPER.readTree(Files.readString(command));String id=value.path("id").asText();if(id.matches("[A-Za-z0-9_-]{1,64}") && handled.add(id)){
                assertEquals(SCHEMA,jdbc.queryForObject("SELECT DATABASE()",String.class));var facts=new LinkedHashMap<String,Object>();facts.put("queryRuns",jdbc.queryForObject("SELECT COUNT(*) FROM business_query_run",Integer.class));facts.put("plans",jdbc.queryForList("SELECT id,status,item_count,confirmed_at FROM dispatch_plan"));facts.put("exports",jdbc.queryForList("SELECT id,status,progress_rows FROM query_export_job"));facts.put("noticesRead",jdbc.queryForObject("SELECT COUNT(*) FROM task_inbox_read",Integer.class));facts.put("businessWrites",jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));facts.put("modelCalls",AssistantMcpLiveReviewTest.modelEvidenceSnapshot().size());write("browser-check-"+id+".json",facts);
                if("stop".equals(value.path("type").asText()))done=true;
            }}Thread.sleep(300);
        }
        assertTrue(done,"浏览器验收未发出完成命令，不计为通过");write("browser-model-evidence.json",AssistantMcpLiveReviewTest.modelEvidenceSnapshot());
    }
    static String login() throws Exception {var response=http(null,"POST","/api/auth/login",Map.of("userId","p1reader","password",PASSWORD));var body=JsonUtil.MAPPER.readTree(response.body());assertEquals(0,body.path("code").asInt());String token=body.path("data").path("token").asText();assertFalse(token.isBlank());return token;}
    static JsonNode api(String token,String method,String path,Object body) throws Exception{return JsonUtil.MAPPER.readTree(http(token,method,path,body).body());}
    static HttpResponse<String> http(String token,String method,String path,Object body) throws Exception {return http(token,method,path,body,Map.of());}
    /** 请求号由调用场景建立并保存；HTTP适配器只透传，显式核对时不得重新生成键。 */
    static HttpResponse<String> http(String token,String method,String path,Object body,Map<String,String> headers) throws Exception {var builder=HttpRequest.newBuilder(URI.create(agentUrl+path)).timeout(Duration.ofSeconds(200)).header("Content-Type","application/json");if(token!=null)builder.header("Authorization","Bearer "+token);headers.forEach(builder::header);return HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body))).build(),HttpResponse.BodyHandlers.ofString());}
    static Map<String,JsonNode> events(String source)throws Exception{var result=new LinkedHashMap<String,JsonNode>();for(String block:source.replace("\r\n","\n").split("\n\n")){String type=null,data=null;for(String line:block.split("\n")){if(line.startsWith("event:"))type=line.substring(6).trim();if(line.startsWith("data:"))data=line.substring(5).trim();}if(type!=null && data!=null)result.put(type,JsonUtil.MAPPER.readTree(data));}return result;}
    static void write(String file,Object value){try{Files.writeString(dir.resolve(file),JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(SensitiveData.value(value)));}catch(Exception e){throw new IllegalStateException(e);}}
}
