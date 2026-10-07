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

/** UUID隔离库中的真实MySQL、会话和HTTP MCP契约验收；调查传输测试替身与真实模型联合验收分别记录。 */
@EnabledIfEnvironmentVariable(named="MCP_IT",matches="true")
class BusinessMcpIntegrationTest {
    static final String schema="mcp_it_"+UUID.randomUUID().toString().replace("-","");
    static final String secret=UUID.randomUUID()+"-"+UUID.randomUUID();
    static final String password="true".equals(System.getenv("INVESTIGATION_UI"))?setting("INVESTIGATION_UI_PASSWORD",UUID.randomUUID().toString()):UUID.randomUUID().toString();
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
        confirmedRecord=null;
        assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
        jdbc.update("DELETE FROM dispatch_job");jdbc.update("DELETE FROM business_dispatch_request");jdbc.update("DELETE FROM dispatch_plan_item");jdbc.update("DELETE FROM dispatch_plan");jdbc.update("DELETE FROM dispatch_preview");
        jdbc.update("UPDATE report_sales SET dispatch_status=0,dispatched_at=NULL WHERE tenant_id='T001'");
        jdbc.update("UPDATE app_user SET enabled=true,companies_json='[\"A\"]',permissions_json='[\"report:sales\"]' WHERE user_id='readerA'");
        jdbc.update("UPDATE report_definition SET dispatch_enabled=true WHERE report_id=?",REPORT);
        jdbc.update("UPDATE dispatch_rule SET version=1 WHERE id=1");
    }
    <T>T call(String tool,CurrentUser user,Map<String,Object> args,TypeReference<T> type){return client.call(tool,user,args,type);}
    Map<String,Object> confirmedRecord;
    com.example.report.assistant.BusinessResult businessQuery(CurrentUser user,com.example.report.assistant.BusinessQuery query) {
        return call("business_query",user,Map.of("query",query),new TypeReference<>(){});
    }
    com.example.report.assistant.BusinessQuery readQuery(com.example.report.assistant.BusinessQuery.Domain domain,String company,List<com.example.report.assistant.BusinessQuery.Group> filters) {
        return new com.example.report.assistant.BusinessQuery(domain,com.example.report.assistant.BusinessQuery.View.LIST,List.of(REPORT),company,filters,null,false,1,20,null);
    }
    @Test void generalReportQueryIncludesDispatchedAndIneligibleRowsAndDoesNotWrite() {
        jdbc.update("UPDATE report_sales SET dispatch_status=1 WHERE tenant_id='T001' AND id='1'");
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM report_sales WHERE tenant_id='T001' AND company_code='A'",Long.class);
        var result=businessQuery(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.REPORT,"A",List.of()));
        assertEquals(count,result.total());assertTrue(result.rows().stream().anyMatch(row->"已派单".equals(row.get("status"))));
        assertTrue(result.summary().amountsByCurrency().containsKey("CNY"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan",Integer.class));
    }
    @Test void generalQueryRejectsCompanyReportAndForgedScalarTypes() {
        assertThrows(ApiException.class,()->businessQuery(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.REPORT,"B",List.of())));
        var forbidden=new com.example.report.assistant.BusinessQuery(com.example.report.assistant.BusinessQuery.Domain.REPORT,com.example.report.assistant.BusinessQuery.View.LIST,List.of("rpt-ar-invoice"),"A",List.of(),null,false,1,20,null);
        assertThrows(ApiException.class,()->businessQuery(reader,forbidden));
        var body=json.convertValue(readQuery(com.example.report.assistant.BusinessQuery.Domain.REPORT,"A",List.of()),new TypeReference<Map<String,Object>>(){});body.put("page",1.5);
        assertThrows(ApiException.class,()->call("business_query",reader,Map.of("query",body),new TypeReference<Object>(){}));
        body.put("page",1);body.put("sql","select * from app_user");
        assertThrows(ApiException.class,()->call("business_query",reader,Map.of("query",body),new TypeReference<Object>(){}));
    }
    @Test void dispatchQueriesRespectOwnerAndWorkOrdersRequireActualSuccessfulItem() {
        String request=evidence(true);
        var before=businessQuery(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.DISPATCH,"A",List.of()));
        assertEquals(1,before.total());assertEquals("结果未知",before.rows().get(0).get("status"));
        identities.saveUser(admin,new IdentityStore.UserForm("queryOther","另一个用户",password,Set.of("A"),Set.of("report:sales"),false,true));
        assertEquals(0,businessQuery(identities.resolve("T001","queryOther"),readQuery(com.example.report.assistant.BusinessQuery.Domain.DISPATCH,"A",List.of())).total());
        assertTrue(submit(request).success());
        jdbc.update("UPDATE dispatch_plan_item SET status='SUCCESS' WHERE external_request_id=?",request);
        var orders=businessQuery(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.WORK_ORDER,"A",List.of()));
        assertTrue(orders.rows().stream().anyMatch(row->("WO-"+request).equals(row.get("orderId")) && "待审批".equals(row.get("status"))));
        assertFalse(orders.rows().stream().anyMatch(row->"B".equals(row.get("companyCode"))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM business_dispatch_request",Integer.class));
    }
    @Test void fixedWorkflowSummaryUsesWholeAuthorizedScopeAndCurrentApproverOnly() {
        var q=new com.example.report.assistant.BusinessQuery(com.example.report.assistant.BusinessQuery.Domain.WORK_ORDER,com.example.report.assistant.BusinessQuery.View.SUMMARY,List.of(),"A",List.of(),"orderId",false,1,1,"status");
        var result=businessQuery(admin,q);assertEquals(5,result.total());assertEquals(1,result.rows().size());
        assertEquals(2,result.summary().statusCounts().get("待审批"));assertEquals(1,result.summary().statusCounts().get("已完成"));
        assertEquals(Map.of("林主管（演示）",1,"陈会计（演示）",1),result.summary().pendingApprovers());
        var filter=new com.example.report.assistant.BusinessQuery.Group(List.of(new com.example.report.assistant.BusinessQuery.Filter("orderId","EQ",List.of("WO-DEMO-B01"))));
        assertEquals(0,businessQuery(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.WORK_ORDER,"A",List.of(filter))).total());
    }
    @Test void selfOwnershipUsesAuthenticatedIdentityEvenWhenDisplayNamesMatch() {
        evidence(false);
        // 展示名相同不能把另一人的记录认作本人，管理员查询本人也不应默认返回所有人的记录。
        jdbc.update("UPDATE app_user SET display_name='同名演示用户' WHERE tenant_id='T001' AND user_id IN ('admin','readerA')");
        var own=new com.example.report.assistant.BusinessQuery.Group(List.of(new com.example.report.assistant.BusinessQuery.Filter("createdByMe","EQ",List.of("true"))));
        var other=new com.example.report.assistant.BusinessQuery.Group(List.of(new com.example.report.assistant.BusinessQuery.Filter("createdByMe","EQ",List.of("false"))));
        assertEquals(1,businessQuery(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.DISPATCH,"A",List.of(own))).total());
        assertEquals(0,businessQuery(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.DISPATCH,"A",List.of(other))).total());
        assertEquals(0,businessQuery(admin,readQuery(com.example.report.assistant.BusinessQuery.Domain.DISPATCH,"A",List.of(own))).total());
        assertEquals(1,businessQuery(admin,readQuery(com.example.report.assistant.BusinessQuery.Domain.DISPATCH,"A",List.of(other))).total());
    }
    @Test void boundedQueryFailsRatherThanReturningPartialAggregate() {
        var service=new BusinessReadService(context.getBean(BusinessQueries.class),new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),context.getBean(WorkOrderProvider.class),1,120,context.getBean(BusinessQueryProperties.class));
        assertThrows(ApiException.class,()->service.query(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.REPORT,"A",List.of())));
    }
    @Test void readOnlyDispatchQueryShowsExpiredPendingPlanWithoutMutatingIt() {
        String request=evidence(false);
        jdbc.update("UPDATE dispatch_plan p JOIN dispatch_plan_item i ON i.plan_id=p.id SET p.expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE i.external_request_id=?",request);
        var result=businessQuery(reader,readQuery(com.example.report.assistant.BusinessQuery.Domain.DISPATCH,"A",List.of()));
        assertEquals("已过期",result.rows().get(0).get("planStatus"));
        assertEquals("PENDING",jdbc.queryForObject("SELECT p.status FROM dispatch_plan p JOIN dispatch_plan_item i ON i.plan_id=p.id WHERE i.external_request_id=?",String.class,request));
    }
    Map<String,Object> sourceRecord(String id) {
        var queries=context.getBean(BusinessQueries.class);var report=queries.require(reader,REPORT,false);
        var row=queries.records(reader,REPORT,"ids",Set.of("A"),null,0,500,List.of(id)).get(0);
        return json.convertValue(com.example.report.rule.DispatchCandidateService.toCandidate(report,row,1L,"销售规则",1,"amount > 20"),new TypeReference<LinkedHashMap<String,Object>>(){});
    }
    Map<String,Object> record() { return new LinkedHashMap<>(confirmedRecord==null?sourceRecord("1"):confirmedRecord); }
    String evidence(boolean confirmed) {
        confirmedRecord=sourceRecord("1");
        String plan=UUID.randomUUID().toString().replace("-","");String preview=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO dispatch_preview(id,tenant_id,user_id,source,report_ids,company_codes,query_json,catalog_version,rule_version,permission_version,status,expires_at,created_at,updated_at) VALUES (?,'T001','readerA','agent','[]','[]','{}','v','v','v','ACTIVE',DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW())",preview);
        jdbc.update("INSERT INTO dispatch_plan(id,preview_id,tenant_id,user_id,status,item_count,idempotency_key,created_at,expires_at,confirmed_at,confirmed_by,updated_at,execution_version) VALUES (?,?,'T001','readerA',?,1,?,NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR),?, ?,NOW(),1)",plan,preview,confirmed?"EXECUTING":"PENDING",plan,confirmed?java.time.LocalDateTime.now():null,confirmed?"readerA":null);
        String request=plan+"-item";
        jdbc.update("INSERT INTO dispatch_plan_item(fields_json,plan_id,seq,report_id,report_name,catalog_version,record_id,company_code,rule_id,rule_version,status,external_request_id,updated_at) VALUES ('[]',?,1,?,'销售报表',1,'1','A',1,1,'UNKNOWN',?,NOW())",plan,REPORT,request);
        jdbc.update("UPDATE dispatch_preview SET report_ids=?,company_codes='[\"A\"]' WHERE id=?","[\""+REPORT+"\"]",preview);
        jdbc.update("UPDATE dispatch_plan_item SET amount=?,biz_date=?,fields_json=?,counterparty_json=? WHERE external_request_id=?",confirmedRecord.get("amount"),confirmedRecord.get("date"),com.example.report.common.JsonUtil.toJson(confirmedRecord.get("fields")),confirmedRecord.get("counterparty")==null?null:com.example.report.common.JsonUtil.toJson(confirmedRecord.get("counterparty")),request);
        return request;
    }
    Map<String,Object> submitArgs(String request){return new LinkedHashMap<>(Map.of("requestId",request,"reportId",REPORT,"record",record(),"enforceRules",true,"executionVersion",1));}
    @Test void submittedFieldValuesMustEqualTheConfirmedSnapshot() {
        String request=evidence(true);var args=submitArgs(request);var changed=record();
        changed.put("fields",List.of(Map.of("name","amount","type","decimal","value","1")));args.put("record",changed);
        assertThrows(ApiException.class,()->call("dispatch_submit",reader,args,new TypeReference<Outcome>(){}));
        assertEquals(0,status());
        jdbc.update("UPDATE dispatch_plan_item SET fields_json=? WHERE external_request_id=?",com.example.report.common.JsonUtil.toJson(changed.get("fields")),request);
        assertFalse(call("dispatch_submit",reader,args,new TypeReference<Outcome>(){}).success(),"请求与清单一致仍必须复核真实来源");
        assertEquals(0,status());
        assertTrue(submit(evidence(true)).success(),"完整有效的重新确认仍可成功");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"amount","date"})
    void topLevelFactsCannotBeSubstitutedEvenWhenFieldsWereNotChanged(String field) {
        String request=evidence(true);var args=submitArgs(request);var changed=record();
        changed.put(field,field.equals("amount")?new java.math.BigDecimal("150000"):"2026-09-01");args.put("record",changed);
        assertThrows(ApiException.class,()->call("dispatch_submit",reader,args,new TypeReference<Outcome>(){}));
        assertEquals(0,status());
    }
    Outcome submit(String request){return call("dispatch_submit",reader,submitArgs(request),new TypeReference<>(){});}
    Lookup lookup(String request){return call("dispatch_lookup",reader,Map.of("requestId",request),new TypeReference<>(){});}
    int status(){return jdbc.queryForObject("SELECT dispatch_status FROM report_sales WHERE id=1",Integer.class);}

    Lookup delegated(CurrentUser actor,String owner,String request) {
        return call("dispatch_lookup",actor,Map.of("requestId",request,"requestOperatorId",owner),new TypeReference<>(){});
    }
    @Test void customerFactsTravelOverAuthenticatedMcpAndCannotBroadenCompanyScope() {
        List<com.example.report.catalog.query.FactRow> rows=call("report_records",admin,
                Map.of("reportId","rpt-ar-invoice","mode","cursor","offset",0,"size",500,"companies",List.of("A")),new TypeReference<>(){});
        var row=rows.stream().filter(r -> "INV-2026-0007".equals(r.docNo())).findFirst().orElseThrow();
        var customer=com.example.report.rule.CounterpartyRef.fromFacts(row.facts());
        assertEquals("CUST-003",customer.id());assertEquals("天津某某贸易有限公司",customer.name());
        assertTrue(customer.aliases().contains("天津某某贸易"));assertTrue(rows.stream().allMatch(r -> "A".equals(r.companyCode())));
        assertThrows(ApiException.class,()->call("report_records",reader,Map.of("reportId","rpt-ar-invoice","mode","cursor","offset",0,"size",50),new TypeReference<List<com.example.report.catalog.query.FactRow>>(){}));
    }
    @Test void submittedCustomerMustEqualConfirmedSnapshotAndLiveIdentity() throws Exception {
        String request=evidence(true);var args=submitArgs(request);var payload=record();
        payload.put("counterparty",Map.of("id","forged","name","伪造客户","aliases",List.of()));args.put("record",payload);
        assertThrows(ApiException.class,()->call("dispatch_submit",reader,args,new TypeReference<Outcome>(){}));assertEquals(0,status());
        String old=jdbc.queryForObject("SELECT query_config FROM report_definition WHERE report_id=?",String.class,REPORT);
        try {
            var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(old);
            var fields=(com.fasterxml.jackson.databind.node.ArrayNode)config.get("fields");
            fields.addObject().put("name","counterpartyId").put("column","product_name").put("type","string").put("description","测试客户标识");
            fields.addObject().put("name","counterpartyName").put("column","product_name").put("type","string").put("description","测试客户名称");
            jdbc.update("UPDATE report_definition SET query_config=? WHERE report_id=?",json.writeValueAsString(config),REPORT);
            var frozen=Map.of("id","different-customer","name","客户","aliases",List.of());
            jdbc.update("UPDATE dispatch_plan_item SET counterparty_json=? WHERE external_request_id=?",json.writeValueAsString(frozen),request);
            payload.put("counterparty",frozen);
            Outcome result=call("dispatch_submit",reader,args,new TypeReference<>(){});
            assertFalse(result.success());assertEquals("RECORD_CHANGED",result.errorCode());assertEquals(0,status());
        } finally {jdbc.update("UPDATE report_definition SET query_config=? WHERE report_id=?",old,REPORT);}
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
            confirmedRecord=sourceRecord(odd);
            jdbc.update("UPDATE dispatch_plan_item SET record_id=?,fields_json=? WHERE external_request_id=?",odd,com.example.report.common.JsonUtil.toJson(confirmedRecord.get("fields")),request);
            var args=submitArgs(request);
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
            assertEquals(Set.of("report_catalog","report_page","report_records","report_probe","dispatch_submit","dispatch_lookup","business_query"),sdk.listTools().tools().stream().map(io.modelcontextprotocol.spec.McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet()));
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

    /** 真实会话→调查HTTP→当前Spring AI工具传输→真实HTTP MCP核对，模型响应由本机测试端点控制。 */
    @Test void investigationHttpUsesReadOnlyMcpAndReplaysWithoutAnotherModelRun() throws Exception {
        investigationHttp(false);
    }

    /** 需要有效真实端点时显式启用，不把本机传输响应计为真实模型结果。 */
    @Test
    @EnabledIfEnvironmentVariable(named="INVESTIGATION_JOINT",matches="true")
    void investigationRealModelAndHttpMcpJointAcceptance() throws Exception {investigationHttp(true);}

    @Test
    @EnabledIfEnvironmentVariable(named="INVESTIGATION_JOINT",matches="true")
    void investigationRealModelAndHttpMcpJointAcceptanceRejected() throws Exception {investigationHttp(true,"FAILED");}

    @Test
    @EnabledIfEnvironmentVariable(named="INVESTIGATION_JOINT",matches="true")
    void investigationRealModelAndHttpMcpJointAcceptanceUnknown() throws Exception {investigationHttp(true,"UNKNOWN");}

    /** 浏览器验证也限定到此UUID隔离库；通过本地停止标记关闭，不影响日常Demo账号和数据。 */
    @Test
    @EnabledIfEnvironmentVariable(named="INVESTIGATION_UI",matches="true")
    void investigationBrowserSession() throws Exception {investigationBrowserFullWorkflow();}

    /** 启动完整前端所用的真实链路；人工浏览器步骤只通过UI操作，文件命令仅用于隔离库故障准备和独立取证。 */
    private void investigationBrowserFullWorkflow() throws Exception {
        var directory=java.nio.file.Path.of("target","investigation-browser-"+UUID.randomUUID().toString());java.nio.file.Files.createDirectories(directory);
        var ready=java.nio.file.Path.of("target","investigation-full-ui-ready.json");var command=java.nio.file.Path.of("target","investigation-full-ui-command.json");var response=java.nio.file.Path.of("target","investigation-full-ui-response.json");
        java.nio.file.Files.deleteIfExists(ready);java.nio.file.Files.deleteIfExists(command);java.nio.file.Files.deleteIfExists(response);
        var agentArgs=java.util.stream.Stream.concat(Arrays.stream(args).filter(a -> !a.startsWith("--business.remote.enabled=") && !a.startsWith("--server.port=")),java.util.stream.Stream.of(
                "--server.port=18180","--business.remote.enabled=true","--business.remote.url="+base,"--spring.flyway.enabled=false",
                "--spring.ai.openai.api-key="+setting("LLM_API_KEY",""),"--spring.ai.openai.base-url="+setting("LLM_BASE_URL",""),
                "--spring.ai.openai.chat.options.model="+setting("LLM_MODEL","deepseek-v4.1-flash"),"--agent.llm.mock=false","--agent.semantic.mode=active",
                "--agent.semantic.native-schema=false","--agent.semantic.thinking-enabled=false","--agent.investigation.poll-ms=100")).toArray(String[]::new);
        try(var agent=(ServletWebServerApplicationContext)new SpringApplicationBuilder(com.example.report.ReportApplication.class).profiles("real","mcp").run(agentArgs)) {
            java.nio.file.Files.writeString(ready,json.writeValueAsString(Map.of("agentUrl","http://127.0.0.1:18180","userId","readerA","schema",schema,"evidenceDirectory",directory.toAbsolutePath().toString())));
            long deadline=System.nanoTime()+TimeUnit.HOURS.toNanos(2);var processed=new HashSet<String>();String beforeInvestigation=null;boolean finished=false;
            while(!finished && System.nanoTime()<deadline) {
                if(!java.nio.file.Files.exists(command)) {Thread.sleep(200);continue;}
                var input=json.readTree(java.nio.file.Files.readString(command));String id=input.path("id").asText();if(!id.matches("[a-zA-Z0-9_-]{1,64}") || !processed.add(id)) {Thread.sleep(200);continue;}
                // 每次操作验证连接确实属于本轮UUID专用库，不能对其他业务库准备故障。
                assertTrue(schema.matches("mcp_it_[a-f0-9]{32}"));assertEquals(schema,jdbc.queryForObject("SELECT DATABASE()",String.class));
                var output=new LinkedHashMap<String,Object>();output.put("commandId",id);
                switch(input.path("type").asText()) {
                    case "change_pending_source" -> {
                        var plans=jdbc.queryForList("SELECT id FROM dispatch_plan WHERE tenant_id='T001' AND user_id='readerA' AND status='PENDING'");assertEquals(1,plans.size());
                        String plan=plans.get(0).get("id").toString();var items=jdbc.queryForList("SELECT record_id FROM dispatch_plan_item WHERE plan_id=? AND report_id=? AND company_code='A' ORDER BY seq",plan,REPORT);assertTrue(items.size()>=2);
                        String record=items.get(0).get("record_id").toString();assertTrue(record.matches("[1-9][0-9]*"));
                        assertEquals(1,jdbc.update("UPDATE report_sales SET amount=1 WHERE id=? AND tenant_id='T001' AND company_code='A' AND dispatch_status=0",record));
                        output.put("fixtureChange",Map.of("planId",plan,"recordId",record,"purpose","SOURCE_CHANGED_AFTER_UI_PLAN_BEFORE_UI_CONFIRM"));
                    }
                    case "before_investigation" -> {
                        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE tenant_id='T001' AND user_id='readerA' AND confirmed_at IS NOT NULL AND success_count>0",Integer.class)>0);
                        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan_item WHERE status='SKIPPED'",Integer.class)>0);
                        beforeInvestigation=investigationBusinessSnapshot();output.put("businessHash",com.example.report.common.Digests.sha256(beforeInvestigation));
                    }
                    case "snapshot" -> { }
                    case "finish" -> {
                        assertNotNull(beforeInvestigation);assertEquals(beforeInvestigation,investigationBusinessSnapshot());
                        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM semantic_turn WHERE user_id='readerA' AND model IS NOT NULL AND latency_ms>0",Integer.class)>0);
                        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM agent_investigation_run WHERE actor_id='readerA' AND status='COMPLETED'",Integer.class)>0);
                        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM agent_investigation_run WHERE actor_id='readerA' AND status='CANCELLED'",Integer.class)>0);
                        output.put("businessUnchangedDuringInvestigation",true);finished=true;
                    }
                    default -> throw new IllegalArgumentException("未允许的浏览器验收命令");
                }
                output.put("plans",jdbc.queryForList("SELECT id,conversation_id,status,success_count,failed_count,confirmed_at FROM dispatch_plan WHERE tenant_id='T001' AND user_id='readerA' ORDER BY created_at"));
                output.put("items",jdbc.queryForList("SELECT plan_id,id,record_id,status,error_code,attempt_count FROM dispatch_plan_item ORDER BY id"));
                output.put("investigations",jdbc.queryForList("SELECT id,status,stop_reason,model_calls,tool_calls,mcp_calls,created_at,finished_at FROM agent_investigation_run WHERE actor_id='readerA' ORDER BY created_at"));
                output.put("semanticTurns",jdbc.queryForList("SELECT request_id,mode,outcome,model,latency_ms FROM semantic_turn WHERE user_id='readerA' ORDER BY created_at"));
                java.nio.file.Files.writeString(response,json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
                java.nio.file.Files.writeString(directory.resolve(id+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            }
            assertTrue(finished,"浏览器未完成全流程，不能将超时等待计为通过");
        } finally {java.nio.file.Files.deleteIfExists(ready);}
    }

    private void investigationHttp(boolean realModel) throws Exception {investigationHttp(realModel,"SUCCESS");}

    /** 在隔离库构造三种业务事实，调查前后比较业务表，实际联合报告保存在忽略的target目录。 */
    private void investigationHttp(boolean realModel,String remote) throws Exception {
        String request=evidence(true);
        if("FAILED".equals(remote)) {
            var amount=jdbc.queryForObject("SELECT amount FROM report_sales WHERE id=1",java.math.BigDecimal.class);
            try {jdbc.update("UPDATE report_sales SET amount=1 WHERE id=1");assertFalse(submit(request).success());}
            finally {jdbc.update("UPDATE report_sales SET amount=? WHERE id=1",amount);}
        } else {
            assertTrue(submit(request).success());
            // 在隔离库中构造已受理但尚未给出终态的业务事实，UNKNOWN不能被报告为失败。
            if("UNKNOWN".equals(remote)) jdbc.update("UPDATE business_dispatch_request SET status='PROCESSING' WHERE request_id=?",request);
        }
        var item=jdbc.queryForMap("SELECT plan_id,id FROM dispatch_plan_item WHERE external_request_id=?",request);
        String plan=item.get("plan_id").toString();jdbc.update("UPDATE dispatch_plan SET status='REVIEW_REQUIRED',success_count=0,failed_count=1 WHERE id=?",plan);
        // 模拟业务成功回执丢失，调查不得把本地UNKNOWN重新发送为派单写请求。
        jdbc.update("UPDATE dispatch_plan_item SET status='UNKNOWN',error_code='TRANSPORT_TIMEOUT' WHERE plan_id=?",plan);
        String businessBefore=investigationBusinessSnapshot();int sourceBefore=status();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var wire=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        wire.createContext("/v1/chat/completions",exchange -> {
            exchange.getRequestBody().readAllBytes();int step=calls.incrementAndGet();
            Map<String,Object> message;
            if(step<=2) {
                String tool=step==1?"investigation_plan_items":"investigation_dispatch_lookup";
                String arguments=step==1?"{\"size\":20}":"{\"itemRefs\":[\"I1\"]}";
                message=Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","call"+step,"type","function","function",Map.of("name",tool,"arguments",arguments))));
            } else if(step==3) message=Map.of("role","assistant","content","查询完成");
            else message=Map.of("role","assistant","content",json.writeValueAsString(Map.of("findings",List.of(Map.of("itemRef","I1","reasonCode","REMOTE_SUCCESS_LOCAL_UNRESOLVED","certainty","VERIFIED","evidenceIds",List.of("E1","E2"),"nextStep","USE_EXISTING_RECONCILE")),"unresolved",List.of())));
            byte[] bytes=json.writeValueAsBytes(Map.of("id","test","object","chat.completion","created",1,"model","test","choices",List.of(Map.of("index",0,"finish_reason","stop","message",message)),"usage",Map.of("prompt_tokens",100,"completion_tokens",50,"total_tokens",150)));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });wire.start();
        String modelBase=realModel?setting("LLM_BASE_URL","https://dashscope.aliyuncs.com/compatible-mode"):"http://127.0.0.1:"+wire.getAddress().getPort();
        String modelKey=realModel?setting("LLM_API_KEY",""):"local-test";
        boolean browser="true".equals(System.getenv("INVESTIGATION_UI"));
        var agentArgs=java.util.stream.Stream.concat(Arrays.stream(args).filter(arg -> !arg.startsWith("--business.remote.enabled=") && !arg.startsWith("--server.port=")),java.util.stream.Stream.of(
                "--server.port="+(browser?18180:0),
                "--agent.investigation.model="+setting("INVESTIGATION_MODEL",""),"--agent.investigation.native-schema="+setting("INVESTIGATION_NATIVE_SCHEMA","false"),"--agent.investigation.thinking-enabled="+setting("INVESTIGATION_THINKING_ENABLED","false"),
                "--business.remote.enabled=true","--business.remote.url="+base,"--spring.flyway.enabled=false","--spring.ai.openai.api-key="+modelKey,"--spring.ai.openai.base-url="+modelBase,
                "--spring.ai.openai.chat.options.model="+(realModel?setting("LLM_MODEL","deepseek-v4.1-flash"):"test"),"--agent.llm.mock=false","--agent.semantic.mode=active","--agent.investigation.poll-ms=100")).toArray(String[]::new);
        try(var agent=(ServletWebServerApplicationContext)new SpringApplicationBuilder(com.example.report.ReportApplication.class).profiles("real","mcp").run(agentArgs)) {
            String url="http://127.0.0.1:"+agent.getWebServer().getPort();var http=HttpClient.newHttpClient();
            var session=identities.login("readerA",password,"investigation-"+UUID.randomUUID());String token=session.token();
            String key=UUID.randomUUID().toString().replace("-","");
            var post=HttpRequest.newBuilder(URI.create(url+"/api/investigations")).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",key)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("planId",plan,"question","调查未知结果，列出依据")))).build();
            var created=http.send(post,HttpResponse.BodyHandlers.ofString());assertEquals(202,created.statusCode(),created.body());
            String id=json.readTree(created.body()).path("data").path("run").path("id").asText();assertFalse(id.isEmpty());
            var get=HttpRequest.newBuilder(URI.create(url+"/api/investigations/"+id)).header("Authorization","Bearer "+token).GET().build();
            com.fasterxml.jackson.databind.JsonNode run=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(realModel?180:25);
            do {run=json.readTree(http.send(get,HttpResponse.BodyHandlers.ofString()).body()).path("data");if(!Set.of("QUEUED","RUNNING").contains(run.path("status").asText())) break;Thread.sleep(100);} while(System.nanoTime()<deadline);
            String expected=switch(remote) {case "FAILED" -> "BUSINESS_REJECTED";case "UNKNOWN" -> "RESULT_UNKNOWN";default -> "REMOTE_SUCCESS_LOCAL_UNRESOLVED";};
            String replayed=json.readTree(http.send(post,HttpResponse.BodyHandlers.ofString()).body()).path("data").path("run").path("id").asText();
            if(realModel) {
                var output=new LinkedHashMap<String,Object>();output.put("evidenceScope","REAL_MODEL_SESSION_HTTP_MCP_ISOLATED_DB");output.put("scenario",remote);output.put("run",run);output.put("modelConfiguration",json.readTree(jdbc.queryForObject("SELECT config_json FROM agent_investigation_run WHERE id=?",String.class,id)));
                output.put("businessBeforeHash",com.example.report.common.Digests.sha256(businessBefore));output.put("businessAfterHash",com.example.report.common.Digests.sha256(investigationBusinessSnapshot()));output.put("businessUnchanged",businessBefore.equals(investigationBusinessSnapshot()));output.put("sameKeyReplayed",id.equals(replayed));output.put("passed","COMPLETED".equals(run.path("status").asText()) && expected.equals(run.path("report").path("findings").path(0).path("reasonCode").asText()));
                output.put("steps",jdbc.queryForList("SELECT seq,kind,status,tool_name,error_code,result_json,duration_ms FROM agent_investigation_step WHERE run_id=? ORDER BY seq",id));
                output.put("evidence",jdbc.queryForList("SELECT evidence_ref,source_type,content_json,truncated FROM agent_investigation_evidence WHERE run_id=? ORDER BY evidence_ref",id));
                var directory=java.nio.file.Path.of("target","investigation-joint");java.nio.file.Files.createDirectories(directory);java.nio.file.Files.writeString(directory.resolve(remote+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            }
            assertEquals("COMPLETED",run.path("status").asText(),run.toString());assertEquals(expected,run.path("report").path("findings").get(0).path("reasonCode").asText());
            assertEquals(id,replayed);if(!realModel) assertEquals(4,calls.get());
            assertEquals(sourceBefore,status());assertEquals(businessBefore,investigationBusinessSnapshot());
            var other=identities.login("readerB",password,"other-investigation-"+UUID.randomUUID());
            var forbidden=HttpRequest.newBuilder(URI.create(url+"/api/investigations/"+id)).header("Authorization","Bearer "+other.token()).GET().build();assertEquals(404,http.send(forbidden,HttpResponse.BodyHandlers.discarding()).statusCode());
            var bad=HttpRequest.newBuilder(URI.create(url+"/api/investigations")).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",key)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("planId",plan,"question","分析","tenantId","OTHER")))).build();assertEquals(400,http.send(bad,HttpResponse.BodyHandlers.discarding()).statusCode());
            if(browser) {
                var ready=java.nio.file.Path.of("target","investigation-ui-ready.json");var stop=java.nio.file.Path.of("target","investigation-ui-stop");java.nio.file.Files.deleteIfExists(stop);
                java.nio.file.Files.writeString(ready,json.writeValueAsString(Map.of("agentUrl",url,"planId",plan,"runId",id,"userId","readerA")));
                try {long limit=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);while(!java.nio.file.Files.exists(stop) && System.nanoTime()<limit) Thread.sleep(250);}
                finally {java.nio.file.Files.deleteIfExists(ready);java.nio.file.Files.deleteIfExists(stop);}
            }
        } finally {wire.stop(0);}
    }
    private String investigationBusinessSnapshot() throws Exception {
        var tables=new LinkedHashMap<String,Object>();
        for(String table:List.of("dispatch_plan","dispatch_plan_item","dispatch_preview","dispatch_rule","report_sales","business_dispatch_request","report_definition")) tables.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        return json.writeValueAsString(tables);
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"amount", "product_name", "sale_date"})
    void changedConfirmedFactsAreRejectedEvenWhenTheRuleStillMatches(String column) {
        Object before=jdbc.queryForObject("SELECT "+column+" FROM report_sales WHERE id=1",Object.class);
        String request=evidence(true);
        try {
            Object value=switch(column){case "amount"->new java.math.BigDecimal("150000.00");case "sale_date"->java.sql.Date.valueOf("2026-09-01");default->"已修改的业务摘要";};
            jdbc.update("UPDATE report_sales SET "+column+"=? WHERE id=1",value);
            var refused=submit(request);assertFalse(refused.success());assertEquals("RECORD_CHANGED",refused.errorCode());assertEquals(0,status());
            assertFalse(submit(request).success(),"明确失败重试仍不得绕过已确认事实");
        } finally {jdbc.update("UPDATE report_sales SET "+column+"=? WHERE id=1",before);}
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
