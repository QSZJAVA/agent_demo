package com.example.business;

import com.example.report.common.ApiException;
import com.example.report.dispatch.DispatchGateway.*;
import com.example.report.mcp.BusinessMcpClient;
import com.example.report.permission.CurrentUser;
import com.example.report.security.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + real HTTP MCP SDK contract tests. Never uses the developer's business schema. */
@EnabledIfEnvironmentVariable(named="MCP_IT",matches="true")
class BusinessMcpIntegrationTest {
    static final String schema="mcp_it_"+UUID.randomUUID().toString().replace("-","");
    static final String secret=UUID.randomUUID()+"-"+UUID.randomUUID();
    static final String password=UUID.randomUUID().toString();
    static final String REPORT="rpt-sales-order";
    static ServletWebServerApplicationContext context;
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static IdentityStore identities;
    static BusinessMcpClient client;
    static CurrentUser admin,reader;
    static String base;
    static String[] args;
    static String setting(String name,String fallback){return System.getenv().getOrDefault(name,fallback);}
    @BeforeAll static void start() {
        args=new String[]{"--server.port=0","--spring.datasource.url=jdbc:mysql://"+setting("DB_HOST","127.0.0.1")+":"+setting("DB_PORT","3306")+"/"+schema+"?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai",
                "--spring.datasource.username="+setting("DB_USERNAME","root"),"--spring.datasource.password="+setting("DB_PASSWORD",""),
                "--spring.data.redis.host="+setting("REDIS_HOST","127.0.0.1"),"--spring.data.redis.port="+setting("REDIS_PORT","6379"),"--spring.data.redis.password="+setting("REDIS_PASSWORD",""),
                "--business.service-token="+secret,"--security.bootstrap-password="+password,"--security.enabled=true","--business.remote.enabled=false","--logging.level.root=WARN"};
        boot();
        admin=identities.resolve("T001","admin");
        identities.saveUser(admin,new IdentityStore.UserForm("readerA","Reader A",password,Set.of("A"),Set.of("report:sales"),false,true));
        identities.saveUser(admin,new IdentityStore.UserForm("readerB","Reader B",password,Set.of("B"),Set.of("report:sales"),false,true));
        reader=identities.resolve("T001","readerA");
    }
    static void boot() {
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(BusinessApplication.class)
                .properties("spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.model.image=none",
                        "spring.ai.model.moderation=none","spring.ai.model.audio.speech=none","spring.ai.model.audio.transcription=none").run(args);
        jdbc=context.getBean(JdbcTemplate.class); json=context.getBean(ObjectMapper.class); identities=context.getBean(IdentityStore.class);
        base="http://127.0.0.1:"+context.getWebServer().getPort(); client=new BusinessMcpClient(json,base,secret);
    }
    @AfterAll static void stop() {
        if(client!=null)client.close();
        if(context!=null)context.close();
        if(schema.matches("mcp_it_[a-f0-9]{32}")) {
            var cleanup=new JdbcTemplate(new DriverManagerDataSource(args[1].substring("--spring.datasource.url=".length()),setting("DB_USERNAME","root"),setting("DB_PASSWORD","")));
            assertEquals(schema,cleanup.queryForObject("SELECT DATABASE()",String.class));cleanup.execute("DROP DATABASE `"+schema+"`");
        }
    }
    @BeforeEach void reset() {
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        jdbc.update("DELETE FROM dispatch_job");jdbc.update("DELETE FROM business_dispatch_request");jdbc.update("DELETE FROM dispatch_plan_item");jdbc.update("DELETE FROM dispatch_plan");jdbc.update("DELETE FROM dispatch_preview");
        jdbc.update("UPDATE report_sales SET dispatch_status=0,dispatched_at=NULL WHERE tenant_id='T001'");
        jdbc.update("UPDATE app_user SET enabled=true,companies_json='[\"A\"]',permissions_json='[\"report:sales\"]' WHERE user_id='readerA'");
        jdbc.update("UPDATE report_definition SET dispatch_enabled=true WHERE report_id=?",REPORT);
        jdbc.update("UPDATE dispatch_rule SET version=1 WHERE id=1");
    }
    <T>T call(String tool,CurrentUser user,Map<String,Object> args,TypeReference<T> type){return client.call(tool,user,args,type);}
    Map<String,Object> record() {
        return new LinkedHashMap<>(Map.of("reportId",REPORT,"reportName","销售报表","recordId","1","docNo","SO2026001","companyCode","A","amount",128000,"ruleId",1,"ruleVersion",1,"catalogVersion",1));
    }
    String evidence(boolean confirmed) {
        String plan=UUID.randomUUID().toString().replace("-","");String preview=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO dispatch_preview(id,tenant_id,user_id,source,report_ids,company_codes,query_json,catalog_version,rule_version,permission_version,status,expires_at,created_at,updated_at) VALUES (?,'T001','readerA','agent','[]','[]','{}','v','v','v','ACTIVE',DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW())",preview);
        jdbc.update("INSERT INTO dispatch_plan(id,preview_id,tenant_id,user_id,status,item_count,idempotency_key,created_at,expires_at,confirmed_at,confirmed_by,updated_at,execution_version) VALUES (?,?,'T001','readerA',?,1,?,NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR),?, ?,NOW(),1)",plan,preview,confirmed?"EXECUTING":"PENDING",plan,confirmed?java.time.LocalDateTime.now():null,confirmed?"readerA":null);
        String request=plan+"-item";
        jdbc.update("INSERT INTO dispatch_plan_item(plan_id,seq,report_id,report_name,catalog_version,record_id,company_code,rule_id,rule_version,status,external_request_id,updated_at) VALUES (?,1,?,'销售报表',1,'1','A',1,1,'UNKNOWN',?,NOW())",plan,REPORT,request);
        jdbc.update("UPDATE dispatch_preview SET report_ids=?,company_codes='[\"A\"]' WHERE id=?","[\""+REPORT+"\"]",preview);
        return request;
    }
    Map<String,Object> submitArgs(String request){return new LinkedHashMap<>(Map.of("requestId",request,"reportId",REPORT,"record",record(),"enforceRules",true,"executionVersion",1));}
    Outcome submit(String request){return call("dispatch_submit",reader,submitArgs(request),new TypeReference<>(){});}
    Lookup lookup(String request){return call("dispatch_lookup",reader,Map.of("requestId",request),new TypeReference<>(){});}
    int status(){return jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=1",Integer.class);}

    Lookup delegated(CurrentUser actor,String owner,String request) {
        return call("dispatch_lookup",actor,Map.of("requestId",request,"requestOperatorId",owner),new TypeReference<>(){});
    }
    @Test void adminCanLookupDisabledOwnerWithoutReactivationOrResend() {
        String request=evidence(true);assertTrue(submit(request).success());
        jdbc.update("UPDATE app_user SET enabled=false WHERE user_id='readerA'");
        assertEquals(LookupStatus.SUCCESS,delegated(admin,"readerA",request).status());
        assertThrows(ApiException.class,()->lookup(request));assertThrows(ApiException.class,()->submit(request));
        assertEquals(1,status());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM app_user WHERE user_id='readerA'",Boolean.class));
    }
    @Test void adminLookupRequiresTenantPlanCompanyAndReportGrants() {
        String request=evidence(true);submit(request);
        assertThrows(ApiException.class,()->delegated(reader,"readerA",request));
        assertThrows(ApiException.class,()->delegated(admin,"readerB",request));
        assertThrows(ApiException.class,()->delegated(admin,"readerA","unknown-request"));
        identities.saveUser(admin,new IdentityStore.UserForm("limitedAdmin","Limited",password,Set.of("B"),Set.of("*"),true,true));
        final var limited=identities.resolve("T001","limitedAdmin");
        assertThrows(ApiException.class,()->delegated(limited,"readerA",request));
        identities.saveUser(admin,new IdentityStore.UserForm("limitedAdmin","Limited",null,Set.of("A"),Set.of(),true,true));
        final var noReports=identities.resolve("T001","limitedAdmin");
        assertThrows(ApiException.class,()->delegated(noReports,"readerA",request));
        var foreign=new CurrentUser("T002","admin","",Set.of("A"),Set.of("*"),true);
        assertThrows(ApiException.class,()->delegated(foreign,"readerA",request));
    }
    @Test void adminCanProveUnsentRequestForDisabledOwnerIncludingMissingIntentId() {
        String request=evidence(true);
        jdbc.update("UPDATE app_user SET enabled=false WHERE user_id='readerA'");
        assertEquals(LookupStatus.NOT_FOUND,delegated(admin,"readerA",request).status());
        String plan=jdbc.queryForObject("SELECT plan_id FROM dispatch_plan_item WHERE external_request_id=?",String.class,request);
        String item=jdbc.queryForObject("SELECT id FROM dispatch_plan_item WHERE external_request_id=?",String.class,request);
        jdbc.update("UPDATE dispatch_plan_item SET external_request_id=NULL WHERE plan_id=?",plan);
        assertEquals(LookupStatus.NOT_FOUND,delegated(admin,"readerA",plan+"-"+item).status());
        assertEquals(0,status());
    }
    @Test void adjacentLargeReportIdsRemainStringsAndOnlyTheSelectedRecordIsDispatched() {
        String even="9007199254740992",odd="9007199254740993";
        try {
            for(String id:List.of(even,odd)) jdbc.update("INSERT INTO report_sales(id,tenant_id,company_code,order_no,product_name,amount,sale_date,dispatch_status) "
                    +"SELECT ?,tenant_id,company_code,CONCAT('BIG-',?),product_name,amount,sale_date,0 FROM report_sales WHERE id=1",id,id);
            Map<String,Object> page=call("report_page",reader,Map.of("reportCode","sales","page",1,"size",200),new TypeReference<>(){});
            var records=(List<Map<String,Object>>)page.get("records");
            assertTrue(records.stream().anyMatch(row->even.equals(row.get("id"))));
            assertTrue(records.stream().anyMatch(row->odd.equals(row.get("id"))));
            assertTrue(records.stream().allMatch(row->row.get("id") instanceof String));
            String request=evidence(true);
            jdbc.update("UPDATE dispatch_plan_item SET record_id=? WHERE external_request_id=?",odd,request);
            var args=submitArgs(request);var selected=record();selected.put("recordId",odd);selected.put("docNo","BIG-"+odd);args.put("record",selected);
            Outcome result=call("dispatch_submit",reader,args,new TypeReference<>(){});
            assertTrue(result.success());
            assertEquals(1,jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=?",Integer.class,odd));
            assertEquals(0,jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=?",Integer.class,even));
        } finally {jdbc.update("DELETE FROM report_sales WHERE id IN (?,?)",even,odd);}
    }

    @Test void standardMcpInitializationAndDiscovery() throws Exception {
        var transport=io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization","Bearer "+secret)).openConnectionOnStartup(false).build();
        try(var sdk=io.modelcontextprotocol.client.McpClient.sync(transport).build()) {
            assertEquals("report-business-service",sdk.initialize().serverInfo().name());
            assertEquals(Set.of("report_catalog","report_page","report_records","report_probe","dispatch_submit","dispatch_lookup"),sdk.listTools().tools().stream().map(io.modelcontextprotocol.spec.McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void readinessRequiresAuthenticatedProtocolAndNeverMutatesBusinessData() {
        assertTrue(client.available());
        try(var invalid=new BusinessMcpClient(json,base,UUID.randomUUID()+"-"+UUID.randomUUID())) {
            assertFalse(invalid.available(),"Public health alone must not hide an invalid service token");
        }
        assertEquals(0,status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
    }

    @Test void delayedGatewayCannotAdoptAReplacementExecutionsVersion() {
        String request=evidence(true);
        var gateway=new com.example.report.mcp.McpDispatchGateway(client);
        var candidate=json.convertValue(record(),com.example.report.rule.Candidate.class);
        var report=context.getBean(BusinessQueries.class).require(reader,REPORT,false);
        var delayed=new com.example.report.dispatch.DispatchGateway.DispatchRequest("T001",request,report,candidate,true,1);
        jdbc.update("UPDATE dispatch_plan SET execution_version=2 WHERE id=(SELECT plan_id FROM dispatch_plan_item WHERE external_request_id=?)",request);
        assertThrows(ApiException.class,()->gateway.dispatch(reader,delayed));
        assertEquals(0,status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
        assertTrue(gateway.dispatch(reader,new com.example.report.dispatch.DispatchGateway.DispatchRequest("T001",request,report,candidate,true,2)).success());
        assertEquals(1,status());
    }

    @Test void authenticatedAgentHttpJobUsesRealMcpAndReplaysWithoutRedispatch() throws Exception {
        // The model bean is configured but never invoked; this checks HTTP jobs and business mutation only.
        var agentArgs=java.util.stream.Stream.concat(Arrays.stream(args).filter(arg->!arg.startsWith("--business.remote.enabled=")),java.util.stream.Stream.of(
                "--business.remote.enabled=true","--business.remote.url="+base,"--spring.flyway.enabled=false",
                "--spring.ai.openai.api-key="+UUID.randomUUID(),"--spring.ai.openai.base-url=http://127.0.0.1:1",
                "--agent.llm.mock=false","--agent.semantic.mode=active","--agent.dispatch-jobs-poll-ms=100")) .toArray(String[]::new);
        try(var agent=(ServletWebServerApplicationContext)new SpringApplicationBuilder(com.example.report.ReportApplication.class).profiles("real","mcp").run(agentArgs)) {
            String agentBase="http://127.0.0.1:"+agent.getWebServer().getPort();
            var http=HttpClient.newHttpClient();
            var login=http.send(HttpRequest.newBuilder(URI.create(agentBase+"/api/auth/login")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("userId","readerA","password",password)))).build(),HttpResponse.BodyHandlers.ofString());
            String token=json.readTree(login.body()).path("data").path("token").asText();assertFalse(token.isBlank());
            String key=UUID.randomUUID().toString().replace("-","");
            var post=HttpRequest.newBuilder(URI.create(agentBase+"/api/dispatch/jobs")).header("Authorization","Bearer "+token)
                    .header("Content-Type","application/json").header("Idempotency-Key",key)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("action","DIRECT","reportId",REPORT,"recordIds",List.of("1"))))).build();
            var created=json.readTree(http.send(post,HttpResponse.BodyHandlers.ofString()).body());assertEquals(0,created.path("code").asInt(-1),created.toString());
            String id=created.path("data").path("id").asText();assertFalse(id.isBlank());
            var get=HttpRequest.newBuilder(URI.create(agentBase+"/api/dispatch/jobs/"+id)).header("Authorization","Bearer "+token).GET().build();
            com.fasterxml.jackson.databind.JsonNode job=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            do { job=json.readTree(http.send(get,HttpResponse.BodyHandlers.ofString()).body()).path("data"); if(Set.of("SUCCEEDED","FAILED").contains(job.path("status").asText()))break;Thread.sleep(50); } while(System.nanoTime()<deadline);
            assertEquals("SUCCEEDED",job.path("status").asText(),job.toString());assertEquals(1,job.path("result").path("successCount").asInt());
            assertEquals(id,json.readTree(http.send(post,HttpResponse.BodyHandlers.ofString()).body()).path("data").path("id").asText());
            assertEquals(1,status());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
        }
    }
    @Test void requiresMachineCredentialAndRejectsBrowserOriginAndOversizedBodies() throws Exception {
        var http=HttpClient.newHttpClient();
        assertEquals(401,http.send(HttpRequest.newBuilder(URI.create(base+"/mcp")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(403,http.send(HttpRequest.newBuilder(URI.create(base+"/mcp")).header("Authorization","Bearer "+secret).header("Origin","https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(413,http.send(HttpRequest.newBuilder(URI.create(base+"/mcp")).header("Authorization","Bearer "+secret).POST(HttpRequest.BodyPublishers.ofString("x".repeat(131073))).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    @Test void catalogAndReportPageEnforceCompanyAndReportPermissions() {
        List<Map<String,Object>> catalog=call("report_catalog",reader,Map.of(),new TypeReference<>(){});
        assertEquals(1,catalog.size());assertEquals(REPORT,catalog.get(0).get("reportId"));
        Map<String,Object> page=call("report_page",reader,Map.of("reportCode","sales","page",1,"size",2),new TypeReference<>(){});
        var rows=(List<Map<String,Object>>)page.get("records");assertEquals(2,rows.size());assertTrue(rows.stream().allMatch(r->"A".equals(r.get("companyCode"))));
        assertThrows(ApiException.class,()->call("report_page",reader,Map.of("reportCode","receivable","page",1,"size",2),new TypeReference<Object>(){}));
    }
    @Test void rejectsTenantSpoofingCompanyEscalationAndForeignIds() {
        CurrentUser forged=new CurrentUser("T002","readerA","fake",Set.of("B"),Set.of("*"),true);
        assertThrows(ApiException.class,()->call("report_catalog",forged,Map.of(),new TypeReference<Object>(){}));
        assertThrows(ApiException.class,()->call("report_records",reader,Map.of("reportId",REPORT,"mode","cursor","offset",0,"size",500,"companies",List.of("B")),new TypeReference<Object>(){}));
        String foreign=jdbc.queryForObject("SELECT id FROM report_sales WHERE company_code='B' LIMIT 1",String.class);
        List<?> rows=call("report_records",reader,Map.of("reportId",REPORT,"mode","ids","offset",0,"size",500,"recordIds",List.of(foreign)),new TypeReference<>(){});
        assertTrue(rows.isEmpty());
    }
    @Test void rejectsUnboundedAndUnknownArguments() {
        assertThrows(RuntimeException.class,()->call("report_page",reader,Map.of("reportCode","sales","page",1,"size",201),new TypeReference<Object>(){}));
        assertThrows(RuntimeException.class,()->call("report_catalog",reader,Map.of("admin",true),new TypeReference<Object>(){}));
    }
    @Test void cannotDispatchWithoutPersistedExplicitConfirmation() {
        assertThrows(ApiException.class,()->submit("invented-request"));assertEquals(0,status());
        String request=evidence(false);assertThrows(ApiException.class,()->submit(request));assertEquals(0,status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
    }
    @Test void confirmationFenceAndRuleModeCannotBeForged() {
        String request=evidence(true);var args=submitArgs(request);args.put("executionVersion",2);
        assertThrows(ApiException.class,()->call("dispatch_submit",reader,args,new TypeReference<Outcome>(){}));
        args.put("executionVersion",1);args.put("enforceRules",false);
        assertThrows(ApiException.class,()->call("dispatch_submit",reader,args,new TypeReference<Outcome>(){}));assertEquals(0,status());
    }
    @Test void commitsSourceAndResultOnceUnderConcurrentDuplicates() throws Exception {
        String request=evidence(true);
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<Outcome>> futures=new ArrayList<>();for(int i=0;i<4;i++)futures.add(pool.submit(()->submit(request)));
            for(var future:futures)assertTrue(future.get(20,TimeUnit.SECONDS).success());
        } finally{pool.shutdownNow();}
        assertEquals(1,status());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
        assertEquals(LookupStatus.SUCCESS,lookup(request).status());
    }
    @Test void sameRequestWithChangedPayloadIsRejected() {
        String request=evidence(true);assertTrue(submit(request).success());
        var args=submitArgs(request);var record=record();record.put("recordId","2");args.put("record",record);
        var error=assertThrows(ApiException.class,()->call("dispatch_submit",reader,args,new TypeReference<Outcome>(){}));assertEquals(409,error.getCode());
        assertEquals(0,jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=2",Integer.class));
    }
    @Test void freshRuleAndPermissionChangesBlockMutation() {
        String request=evidence(true);jdbc.update("UPDATE dispatch_rule SET version=2 WHERE id=1");assertFalse(submit(request).success());assertEquals(0,status());
        jdbc.update("UPDATE dispatch_rule SET version=1 WHERE id=1");jdbc.update("UPDATE app_user SET companies_json='[]' WHERE user_id='readerA'");
        assertThrows(ApiException.class,()->submit(request));assertEquals(0,status());
    }
    @Test void sourceStateAndDisabledReportBlockMutation() {
        String request=evidence(true);jdbc.update("UPDATE report_sales SET amount=1 WHERE id=1");
        try{assertFalse(submit(request).success());assertEquals(0,status());}finally{jdbc.update("UPDATE report_sales SET amount=128000 WHERE id=1");}
        jdbc.update("UPDATE report_definition SET dispatch_enabled=false WHERE report_id=?",REPORT);
        assertFalse(submit(request).success());assertEquals(0,status());
    }
    @Test void confirmedFailureCanRetrySameIdAndOtherOperatorCannotReadResult() {
        String request=evidence(true);jdbc.update("UPDATE report_sales SET dispatch_status=1 WHERE id=1");assertFalse(submit(request).success());
        jdbc.update("UPDATE report_sales SET dispatch_status=0 WHERE id=1");assertTrue(submit(request).success());
        CurrentUser other=identities.resolve("T001","readerB");Lookup hidden=call("dispatch_lookup",other,Map.of("requestId",request),new TypeReference<>(){});
        assertEquals(LookupStatus.NOT_FOUND,hidden.status());
    }
    @Test void resultSurvivesServiceRestartAndAllowsReconciliationWithoutResend() {
        String request=evidence(true);submit(request); // caller deliberately discards the acknowledgement
        client.close();context.close();boot();
        assertEquals(LookupStatus.SUCCESS,lookup(request).status());assertTrue(submit(request).success());
        assertEquals(1,status());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
    }
    @Test void sessionsAreHashedRevocableAndReflectCurrentPermissions() {
        String userId="login"+UUID.randomUUID().toString().replace("-","");
        identities.saveUser(admin,new IdentityStore.UserForm(userId,"Login test",password,Set.of("A"),Set.of("report:sales"),false,true));
        var session=identities.login(userId,password,"test-"+userId);
        assertEquals(userId,identities.authenticate(session.token()).userId());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_session WHERE token_hash=?",Integer.class,session.token()));
        identities.logout(session.token());assertThrows(ApiException.class,()->identities.authenticate(session.token()));
        assertFalse(Passwords.matches("wrong",Passwords.hash(password)));
    }
    @Test void monetaryFactsKeepDecimalPrecisionAcrossMcp() {
        jdbc.update("UPDATE report_sales SET amount=100000000000001.01 WHERE id=1");
        try {
            List<com.example.report.catalog.query.FactRow> rows=call("report_records",reader,Map.of("reportId",REPORT,"mode","ids","offset",0,"size",1,"recordIds",List.of("1")),new TypeReference<>(){});
            assertEquals(new java.math.BigDecimal("100000000000001.01"),rows.get(0).amount());
            assertEquals(new java.math.BigDecimal("100000000000001.01"),rows.get(0).facts().get("amount"));
        }finally{jdbc.update("UPDATE report_sales SET amount=128000 WHERE id=1");}
    }
    @Test void lookupWaitsForAnUncommittedResultInsteadOfReportingNotFound() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("INSERT INTO business_dispatch_request(tenant_id,request_id,operator_id,payload_hash,report_id,record_id,status) VALUES ('T001','inflight','readerA',? ,?,'1','SUCCESS')")) {
                statement.setString(1,"a".repeat(64));statement.setString(2,REPORT);statement.executeUpdate();
            }
            var result=pool.submit(()->lookup("inflight"));
            assertThrows(TimeoutException.class,()->result.get(300,TimeUnit.MILLISECONDS));
            connection.commit();assertEquals(LookupStatus.SUCCESS,result.get(10,TimeUnit.SECONDS).status());
        }finally{pool.shutdownNow();}
    }
}
