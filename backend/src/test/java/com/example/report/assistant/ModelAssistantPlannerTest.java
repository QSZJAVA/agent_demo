package com.example.report.assistant;

import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.semantic.DialogueState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 模型请求边界和有界修正预算测试，使用传输替身检查真实规划代码，不计为真实模型泛化证据。 */
class ModelAssistantPlannerTest {
    @Test void previousPageIsCompleteOnlyWhenTrustedTotalAndSuccessfulFirstPageAgree() throws Exception {
        var state=new DialogueState();state.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),"A",List.of(),null,false,1,20,null));
        state.setBusinessReferences(List.of(Map.of("recordId","a"),Map.of("recordId","b"),Map.of("recordId","c")));
        for(Long total:Arrays.asList(null,3L,30L)) {
            state.setBusinessTotalCount(total);var model=new Model(HELP);
            new ModelAssistantPlanner(model,new AgentProperties()).plan("看看金额最高的那笔",state,List.of(),Set.of("A"));
            var context=JsonUtil.MAPPER.readTree(model.prompts.get(0).getUserMessage().getText()).path("previousResult");
            assertEquals(3,context.path("displayedCount").asInt());assertEquals(Objects.equals(total,3L),context.path("allMatchesDisplayed").asBoolean());
        }
        state.setBusinessTotalCount(3L);state.setBusinessUnresolved(true);var failed=new Model(HELP);
        new ModelAssistantPlanner(failed,new AgentProperties()).plan("看看金额最高的那笔",state,List.of(),Set.of("A"));
        assertFalse(JsonUtil.MAPPER.readTree(failed.prompts.get(0).getUserMessage().getText()).at("/previousResult/allMatchesDisplayed").asBoolean());
        state.setBusinessUnresolved(false);state.setBusinessQuery(state.getBusinessQuery().atPage(2));var laterPage=new Model(HELP);
        new ModelAssistantPlanner(laterPage,new AgentProperties()).plan("看看金额最高的那笔",state,List.of(),Set.of("A"));
        assertFalse(JsonUtil.MAPPER.readTree(laterPage.prompts.get(0).getUserMessage().getText()).at("/previousResult/allMatchesDisplayed").asBoolean());
    }
    @Test void businessQueryFilterCannotImplicitlySwitchToDispatchSelection() {
        var state=new DialogueState();state.setAssistantFocus("BUSINESS_QUERY");
        var dispatch=new AssistantPlan(AssistantPlan.Route.DISPATCH,null,false,null);
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("别要差旅费，其他的留下",state,dispatch));
        assertDoesNotThrow(()->AssistantRouteGuard.validate("重新查看可派单候选",state,dispatch));
        assertDoesNotThrow(()->AssistantRouteGuard.validate("取消刚才的清单",state,dispatch));
    }
    @Test void routerReceivesConfiguredDispatchFieldSelectionCapabilities() throws Exception {
        var model=new Model(HELP);var reports=new com.example.report.support.TestCatalog().entries();
        new ModelAssistantPlanner(model,new AgentProperties()).plan("可以怎样调整候选",new DialogueState(),reports,Set.of("A"));
        var input=JsonUtil.MAPPER.readTree(model.prompts.get(0).getUserMessage().getText());
        assertTrue(input.at("/dispatchCapabilities/recordSelectors").toString().contains("FIELDS"));
        assertTrue(input.at("/dispatchFieldsByReport/rpt-expense-claim").toString().contains("amount"));
    }
    @Test void followUpStatusFilterMustKeepPreviousDateBoundsUnlessExplicitlyRemoved() {
        var date=new BusinessQuery.Filter("date","GTE",List.of("2026-01-01"));var status=new BusinessQuery.Filter("status","EQ",List.of("未派单"));
        var state=new DialogueState();state.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(date))),null,false,1,20,null));
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(status))),null,false,1,20,null);
        var plan=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,true,null);
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateRefinement("只要未派单的",state,plan));
        var removal=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,true,null,List.of(new AssistantPlan.FilterRemoval("date","取消日期限制")));
        assertDoesNotThrow(()->AssistantRouteGuard.validateRefinement("取消日期限制，只要未派单的",state,removal));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateRefinement("只要未派单的",state,removal));
    }
    @Test void readOnlyBusinessValidationParticipatesInTheSingleModelRepair() {
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of("r"),null,List.of(),null,false,1,20,null);
        var unique=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("docNo","EQ",List.of("DOC-ONE"))))),null,false,1,20,null);
        var model=new Model(JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,false,null)),JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,unique,false,null)));
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan("展开刚才那笔",new DialogueState(),List.of(),Set.of("A"),draft->{
            reads.incrementAndGet();if(draft.query().conditions().isEmpty())throw new ApiException(422,"匹配到多条记录，请指定准确编号后查看详情");
        });
        assertEquals(unique,result.query());assertEquals(2,reads.get());assertEquals(2,model.prompts.size());
    }
    @Test void allRecordsOfNamedReportMustNotExpandToEveryReport() {
        var reports=new com.example.report.support.TestCatalog().entries();
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(),"A",List.of(),null,false,1,20,null);
        var plan=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,false,null);
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateExplicitReport("A公司全部销售数据",plan,reports));
        assertDoesNotThrow(()->AssistantRouteGuard.validateExplicitReport("全部报表都查，包含销售",plan,reports));
        assertDoesNotThrow(()->AssistantRouteGuard.validateExplicitReport("Show all reports including sales",plan,reports));
        assertDoesNotThrow(()->AssistantRouteGuard.validateExplicitReport("产品名称包含销售这个词",plan,reports));
        assertDoesNotThrow(()->AssistantRouteGuard.validateExplicitReport("不限于销售",plan,reports));
        var correct=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(com.example.report.support.TestCatalog.SALES),"A",List.of(),null,false,1,20,null);
        assertDoesNotThrow(()->AssistantRouteGuard.validateExplicitReport("全部销售数据",new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,correct,false,null),reports));
    }
    @Test void emptyConditionGroupHasActionableRepairAndNativeSchemaRejectsIt() throws Exception {
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),"A",List.of(),null,false,1,20,null);
        var correct=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,false,null);
        var bad=JsonUtil.MAPPER.valueToTree(correct);
        ((com.fasterxml.jackson.databind.node.ObjectNode)bad.get("query")).set("conditions",JsonUtil.MAPPER.readTree("[{\"allOf\":[]}]"));
        var failure=assertThrows(ApiException.class,()->AssistantCodec.plan(bad.toString()));assertTrue(failure.getMessage().contains("conditions应为空数组"));
        var schema=JsonUtil.MAPPER.readTree(AssistantSchema.planSchema());assertEquals(1,schema.at("/properties/query/properties/conditions/items/properties/allOf/minItems").asInt());
        var model=new Model(bad.toString(),JsonUtil.toJson(correct));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan("列出全部报表数据",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(2,model.prompts.size());
    }
    @Test void newDomainCannotInheritAnUnmentionedOrNegatedReport() {
        var reports=new com.example.report.support.TestCatalog().entries();String sales=com.example.report.support.TestCatalog.SALES;
        var state=new DialogueState();state.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(sales),null,List.of(),null,false,1,20,null));
        var order=new BusinessQuery(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.DETAIL,List.of(sales),null,
                List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("orderId","EQ",List.of("WO-123"))))),null,false,1,20,null);
        var wrong=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,order,false,null);
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateDomainScope("不看销售了，工单WO-123进展如何",state,wrong,reports));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateDomainScope("工单WO-123进展如何",state,wrong,reports));
        assertDoesNotThrow(()->AssistantRouteGuard.validateDomainScope("查询销售对应的工单WO-123",state,wrong,reports));
        assertDoesNotThrow(()->AssistantRouteGuard.validateDomainScope("再看这张工单",state,new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,order,true,null),reports));
        state.setBusinessQuery(order);
        var salesQuery=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(sales),"A",List.of(),null,false,1,20,null);
        assertDoesNotThrow(()->AssistantRouteGuard.validateDomainScope("Go back to company A's sales",state,new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,salesQuery,false,null),reports));
    }
    @Test void sortedDetailMustIdentifyTheTargetRatherThanTruncateSeveralMatches() {
        var status=new BusinessQuery.Filter("status","EQ",List.of("未派单"));
        var wrong=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(status))),"amount",true,1,1,null);
        var correct=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(status,new BusinessQuery.Filter("recordId","EQ",List.of("x"))))),"amount",true,1,1,null);
        var model=new Model(JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,wrong,false,null)),JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,correct,false,null)));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan("展开最大金额的记录",new DialogueState(),List.of(),Set.of("A")).query());
        assertEquals(2,model.prompts.size());
    }
    @Test void ownershipAndPlanStatusCannotBecomeAlternativeSetsWithoutDisjunctionEvidence() {
        var owner=new BusinessQuery.Filter("createdByMe","EQ",List.of("true"));var status=new BusinessQuery.Filter("planStatus","EQ",List.of("已取消"));
        var wrong=new BusinessQuery(BusinessQuery.Domain.DISPATCH,BusinessQuery.View.LIST,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(owner)),new BusinessQuery.Group(List.of(status))),null,false,1,20,null);
        var correct=new BusinessQuery(BusinessQuery.Domain.DISPATCH,BusinessQuery.View.LIST,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(owner,status))),null,false,1,20,null);
        var model=new Model(JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,wrong,false,null)),JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,correct,false,null)));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan("本人创建且已取消的派单条目",new DialogueState(),List.of(),Set.of("A")).query());
        assertEquals(2,model.prompts.size());
        assertDoesNotThrow(()->AssistantRouteGuard.validate("本人创建或已取消的条目",new DialogueState(),new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,wrong,false,null)));
    }
    @Test void explicitUnauthorizedCompanyCannotBeSilentlyDroppedAndGetsOneRepair() {
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),null,List.of(),null,false,1,20,null);
        var wrong=JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,true,null));
        var refusal=JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"当前没有该公司权限，请选择授权范围"));
        var model=new Model(wrong,refusal);var state=new DialogueState();state.setBusinessQuery(query);
        assertEquals(AssistantPlan.Route.CLARIFY,new ModelAssistantPlanner(model,new AgentProperties()).plan("C公司的也一起给我",state,List.of(),Set.of("A")).route());
        assertEquals(2,model.prompts.size());
        assertDoesNotThrow(()->AssistantRouteGuard.validateCompanies("查询北京某某科技有限公司的应收",new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,false,null),Set.of("A")));
    }
    @Test void rangeBoundsSplitAcrossOrAreRepairedRatherThanReturningAllRows() {
        var low=new BusinessQuery.Filter("amount","GTE",List.of("25"));var high=new BusinessQuery.Filter("amount","LTE",List.of("75"));
        var split=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(low)),new BusinessQuery.Group(List.of(high))),null,false,1,20,null);
        var combined=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(low,high))),null,false,1,20,null);
        var model=new Model(JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,split,false,null)),JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,combined,false,null)));
        assertEquals(combined,new ModelAssistantPlanner(model,new AgentProperties()).plan("Show amounts between 25 and 75",new DialogueState(),List.of(),Set.of("A")).query());
        assertEquals(2,model.prompts.size());
        var twoRanges=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),null,List.of(new BusinessQuery.Group(List.of(low,high)),new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("100")),new BusinessQuery.Filter("amount","LT",List.of("200"))))),null,false,1,20,null);
        assertDoesNotThrow(()->AssistantRouteGuard.validate("25至75之间或100至200之间",new DialogueState(),new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,twoRanges,false,null)));
    }
    static final String HELP="{\"route\":\"HELP\",\"query\":null,\"followUp\":false,\"clarification\":null,\"removedFilters\":[]}";
    static class Model implements ChatModel {
        final List<Prompt> prompts=new ArrayList<>();final Deque<String> replies=new ArrayDeque<>();
        Model(String... replies){this.replies.addAll(List.of(replies));}
        @Override public ChatOptions getDefaultOptions(){return ChatOptions.builder().model("test-only").build();}
        @Override public ChatResponse call(Prompt prompt){prompts.add(prompt);return new ChatResponse(List.of(new Generation(new AssistantMessage(replies.removeFirst()))));}
    }
    @Test void malformedPlanStopsAfterTwoRepairsAndNoImplicitDispatch() {
        var model=new Model("{\"route\":\"CALL_SQL\"}",HELP);var parser=new ModelAssistantPlanner(model,new AgentProperties());
        assertEquals(AssistantPlan.Route.HELP,parser.plan("你能做什么",new DialogueState(),List.of(),Set.of("A")).route());assertEquals(2,model.prompts.size());
        assertTrue(model.prompts.get(1).getUserMessage().getText().contains("rejectedDraft"));
        var invalid=new Model("{}","{}","{}");assertThrows(ApiException.class,()->new ModelAssistantPlanner(invalid,new AgentProperties()).plan("查询销售数据",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(3,invalid.prompts.size());
    }
    @Test void modelReceivesRedactedTextAndHasNoToolExecutionAuthority() {
        var model=new Model(HELP);var parser=new ModelAssistantPlanner(model,new AgentProperties());
        parser.plan("帮我了解 example.person@example.invalid 的工单",new DialogueState(),List.of(),Set.of("A"));
        String input=model.prompts.get(0).getUserMessage().getText();assertFalse(input.contains("example.person@example.invalid"));
        var options=(org.springframework.ai.openai.OpenAiChatOptions)model.prompts.get(0).getOptions();
        assertFalse(options.getInternalToolExecutionEnabled());assertTrue(options.getToolCallbacks().isEmpty());assertNotNull(options.getResponseFormat());
    }
    @Test void unresolvedContextCannotBeUsedByAReportedFollowUp() {
        String plan="{\"route\":\"BUSINESS_QUERY\",\"query\":"+JsonUtil.toJson(new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(),null,List.of(),null,false,2,20,null))+",\"followUp\":true,\"clarification\":null,\"removedFilters\":[]}";
        var model=new Model(plan,plan,plan);var state=new DialogueState();state.setBusinessUnresolved(true);
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(model,new AgentProperties()).plan("下一页",state,List.of(),Set.of("A")));
        assertNull(state.getBusinessQuery());
    }
    @Test void actualHttpRequestCarriesNativeSchemaAndDisabledThinkingWithoutTools() throws Exception {
        var request=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            request.set(JsonUtil.MAPPER.readTree(exchange.getRequestBody()));
            byte[] body=JsonUtil.toJson(Map.of("id","local","object","chat.completion","created",1,"model","test","choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",HELP))))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var api=org.springframework.ai.openai.api.OpenAiApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).apiKey("local-test").build();
            var model=org.springframework.ai.openai.OpenAiChatModel.builder().openAiApi(api).defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build()).build();
            assertEquals(AssistantPlan.Route.HELP,new ModelAssistantPlanner(model,new AgentProperties()).plan("你能做什么",new DialogueState(),List.of(),Set.of("A")).route());
            assertEquals("json_schema",request.get().at("/response_format/type").asText());
            assertTrue(request.get().at("/response_format/json_schema/strict").asBoolean());
            assertEquals("BUSINESS_QUERY",request.get().at("/response_format/json_schema/schema/properties/route/enum/0").asText());
            assertEquals("disabled",request.get().at("/thinking/type").asText());assertTrue(request.get().path("tools").isMissingNode() || request.get().path("tools").isEmpty());
        } finally {server.stop(0);}
    }
    @Test void previousNumericReferenceIsMaskedOnWireAndRestoredForFollowUp() {
        String id="900000000000000001";var state=new DialogueState();
        state.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),"A",List.of(),null,false,1,20,null));
        state.setBusinessReferences(List.of(Map.of("displayIndex","1","recordId",id,"reportId","r")));
        ChatModel model=new ChatModel(){
            @Override public ChatOptions getDefaultOptions(){return ChatOptions.builder().model("test-only").build();}
            @Override public ChatResponse call(Prompt prompt){
                var input=JsonUtil.toMap(prompt.getUserMessage().getText());assertFalse(prompt.getUserMessage().getText().contains(id));
                String token=((Map<?,?>)((List<?>)input.get("previousRows")).get(0)).get("recordId").toString();
                var q=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of("r"),"A",List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of(token))))),null,false,1,20,null);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,q,true,null))))));
            }
        };
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan("查看第一条详情",state,List.of(),Set.of("A"));
        assertEquals(id,result.query().conditions().get(0).allOf().get(0).values().get(0));
    }
    @Test void selectionCannotSilentlyBecomePositiveBusinessQueryAndGetsOneModelRepair() {
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),null,List.of(),null,false,1,20,null);
        var wrong=JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,false,null));
        var correct=JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.DISPATCH,null,false,null));
        var model=new Model(wrong,correct);var state=new DialogueState();state.setAssistantFocus("DISPATCH");state.setAssistantRoute("CLARIFY");state.setPreviewId("preview");
        var plan=new ModelAssistantPlanner(model,new AgentProperties()).plan("金额超过八万元的先排除",state,List.of(),Set.of("A"));
        assertEquals(AssistantPlan.Route.DISPATCH,plan.route());assertEquals(2,model.prompts.size());
        assertTrue(model.prompts.get(0).getUserMessage().getText().contains("\"lastRoute\":\"DISPATCH\""));
        assertDoesNotThrow(()->AssistantRouteGuard.validate("不要派单，查询全部销售数据",state,new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,false,null)));
    }
    @Test void dispatchFollowUpUsesDispatchStateRatherThanRequiringAnUnrelatedBusinessQuery() {
        var model=new Model(JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.DISPATCH,null,true,null)));
        var state=new DialogueState();state.setAssistantFocus("DISPATCH");state.setPreviewId("preview");
        var plan=new ModelAssistantPlanner(model,new AgentProperties()).plan("恢复指定记录",state,List.of(),Set.of("A"));
        assertEquals(AssistantPlan.Route.DISPATCH,plan.route());assertNull(state.getBusinessQuery());assertEquals(1,model.prompts.size());
    }
    @Test void emptyQueryFollowUpCannotChangeDomainAndIndependentQueryCan() {
        var state=new DialogueState();state.setAssistantFocus("BUSINESS_QUERY");
        var previous=new BusinessQuery(BusinessQuery.Domain.DISPATCH,BusinessQuery.View.LIST,List.of("r"),"A",List.of(),null,false,1,20,null);
        state.setBusinessQuery(previous);state.setBusinessReferences(List.of());
        var changed=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.SUMMARY,List.of("r"),"A",List.of(),null,false,1,20,"companyCode");
        var wrong=JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,changed,true,null));
        var right=JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,previous,true,null));
        var model=new Model(wrong,right);
        assertEquals(BusinessQuery.Domain.DISPATCH,new ModelAssistantPlanner(model,new AgentProperties()).plan("按公司归类",state,List.of(),Set.of("A")).query().domain());
        assertEquals(2,model.prompts.size());assertTrue(model.prompts.get(1).getUserMessage().getText().contains("连续查询必须保留上一查询的数据域"));
        var invalid=new Model(wrong,wrong,wrong);
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(invalid,new AgentProperties()).plan("按公司归类",state,List.of(),Set.of("A")));
        var independent=new Model(JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,changed,false,null)));
        assertEquals(BusinessQuery.Domain.REPORT,new ModelAssistantPlanner(independent,new AgentProperties()).plan("另查销售报表",state,List.of(),Set.of("A")).query().domain());
    }
    @Test void durableQueryKeepsMachineIdsButModelProjectionStillRedactsThem() throws Exception {
        String id="900000000000000001";
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),"A",List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of(id))))),null,false,1,20,null);
        var state=new DialogueState();state.setBusinessQuery(query);
        var saved=com.example.report.operations.SensitiveData.value(state);
        var restored=JsonUtil.MAPPER.treeToValue(saved,DialogueState.class);
        assertEquals(id,restored.getBusinessQuery().conditions().get(0).allOf().get(0).values().get(0));
        assertFalse(com.example.report.operations.SensitiveData.forModel(restored).toString().contains(id));
        assertFalse(com.example.report.operations.SensitiveData.value(Map.of("field","email","operator","EQ","values",List.of("person@example.invalid"))).toString().contains("person@example.invalid"));
    }
}
