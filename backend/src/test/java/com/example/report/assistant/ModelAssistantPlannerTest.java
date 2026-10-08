package com.example.report.assistant;

import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.semantic.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.*;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 统一规划、独立复核、只读反馈与模型请求边界的程序测试；传输替身只验证机制，不证明真实模型语义能力。 */
class ModelAssistantPlannerTest {
    static final String HELP=JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.HELP,null,false,null));
    static final String APPROVED=JsonUtil.toJson(new SemanticReview(true,List.of()));

    /** 分别模拟规划与复核响应，防止把第二次调用当成另一份执行计划；每次请求均留作断言。 */
    static class Model implements ChatModel {
        final List<Prompt> prompts=new ArrayList<>(),planningPrompts=new ArrayList<>(),reviewPrompts=new ArrayList<>();
        final Deque<String> replies=new ArrayDeque<>(),reviews=new ArrayDeque<>();
        Model(String... replies){this.replies.addAll(List.of(replies));}
        Model reject(String evidence,String reason){reviews.add(JsonUtil.toJson(new SemanticReview(false,List.of(new SemanticReview.Issue(evidence,reason)))));return this;}
        @Override public ChatOptions getDefaultOptions(){return ChatOptions.builder().model("test-only").build();}
        @Override public ChatResponse call(Prompt prompt){
            prompts.add(prompt);boolean review=JsonUtil.toMap(prompt.getUserMessage().getText()).containsKey("proposedPlan");
            (review?reviewPrompts:planningPrompts).add(prompt);
            String content=review?(reviews.isEmpty()?APPROVED:reviews.removeFirst()):replies.removeFirst();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }
    static BusinessQuery query(BusinessQuery.Domain domain,BusinessQuery.View view,List<BusinessQuery.Group> groups) {
        return new BusinessQuery(domain,view,List.of("r"),"A",groups,null,false,1,20,null);
    }
    static AssistantPlan read(BusinessQuery query,boolean followUp){return new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,followUp,null);}
    static SemanticIntent intent(Action action,ScopeChange... changes){return new SemanticIntent(1,action,List.of(changes),List.of(),List.of(),List.of(),Clarify.NONE);}
    static AssistantPlan dispatch(DialogueState state,String message,Action action,ScopeChange... changes){
        return AssistantPlan.dispatch(new DispatchDirective(intent(action,changes),DispatchDirective.Source.PREVIEW,AssistantReferences.previewRef(state),List.of(),message));
    }
    static Model repair(AssistantPlan before,AssistantPlan after,String message,String reason,DialogueState state) {
        var model=new Model(JsonUtil.toJson(before),JsonUtil.toJson(after)).reject(message,reason);
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"));
        assertEquals(after,result);assertEquals(2,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        assertTrue(model.planningPrompts.get(1).getUserMessage().getText().contains(reason));return model;
    }

    @Test void routeOnlyAndExecutePlansCannotCrossTheContract() throws Exception {
        var node=JsonUtil.MAPPER.readTree(HELP).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("dispatch");
        assertThrows(ApiException.class,()->AssistantCodec.plan(node.toString()));
        assertThrows(ApiException.class,()->new AssistantPlan(AssistantPlan.Route.DISPATCH,null,false,null));
        var state=new DialogueState();state.setPreviewId("p");var full=dispatch(state,"准备当前候选",Action.PREPARE_DISPATCH);
        assertEquals(full,AssistantCodec.plan(JsonUtil.toJson(full)));
        assertThrows(ApiException.class,()->AssistantCodec.plan(JsonUtil.toJson(full).replace("PREPARE_DISPATCH","EXECUTE")));
        var schema=JsonUtil.MAPPER.readTree(AssistantSchema.planSchema());
        assertNotNull(schema.at("/properties/dispatch/properties/intent/properties/scopeChanges"));
        assertTrue(schema.path("required").toString().contains("dispatch"));
    }
    @Test void previousPageCompletenessRequiresTrustedTotalAndSuccessfulFirstPage() throws Exception {
        var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));
        state.setBusinessReferences(List.of(Map.of("recordId","1"),Map.of("recordId","2")));
        for(Long count:Arrays.asList(null,2L,20L)) {
            state.setBusinessTotalCount(count);var model=new Model(HELP);
            new ModelAssistantPlanner(model,new AgentProperties()).plan("如何选择",state,List.of(),Set.of("A"));
            var context=JsonUtil.MAPPER.readTree(model.planningPrompts.get(0).getUserMessage().getText());
            assertEquals(Objects.equals(count,2L),context.at("/previousResult/allMatchesDisplayed").asBoolean());
        }
        state.setBusinessTotalCount(2L);state.setBusinessUnresolved(true);assertFalse(AssistantReferences.complete(state));
        state.setBusinessUnresolved(false);state.setBusinessQuery(state.getBusinessQuery().atPage(2));assertFalse(AssistantReferences.complete(state));
    }
    @Test void currentCapabilitiesConversationAndSelectionReachBothStages() throws Exception {
        var model=new Model(HELP);var reports=new com.example.report.support.TestCatalog().entries();
        var context=new AssistantPlanningContext(List.of(Map.of("role","user","content","之前保留了服务器")),Map.of("selectedCount",1),p->Map.of("checked",true));
        new ModelAssistantPlanner(model,new AgentProperties()).plan("能怎么处理",new DialogueState(),reports,Set.of("A"),context);
        for(var prompt:model.prompts) {
            var input=JsonUtil.MAPPER.readTree(prompt.getUserMessage().getText());
            assertTrue(input.at("/dispatchCapabilities/recordSelectors").toString().contains("FIELDS"));
            assertTrue(input.path("recentConversation").toString().contains("服务器"));assertEquals(1,input.at("/dispatchSelection/selectedCount").asInt());
        }
        assertTrue(JsonUtil.MAPPER.readTree(model.reviewPrompts.get(0).getUserMessage().getText()).at("/readEvidence/checked").asBoolean());
    }
    @Test void onlyStructuralAndSemanticFailuresUseTwoBoundedRepairs() {
        var invalid=new Model("{}","{}","{}");
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(invalid,new AgentProperties()).plan("查询数据",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(3,invalid.planningPrompts.size());assertEquals(0,invalid.reviewPrompts.size());
        var denied=new Model(HELP,HELP,HELP).reject("查询数据","动作不符").reject("查询数据","仍遗漏查询").reject("查询数据","仍未落实动作");
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(denied,new AgentProperties()).plan("查询数据",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(3,denied.planningPrompts.size());assertEquals(3,denied.reviewPrompts.size());
        var forbidden=new Model(HELP);
        assertEquals(403,assertThrows(ApiException.class,()->new ModelAssistantPlanner(forbidden,new AgentProperties()).plan("查询数据",new DialogueState(),List.of(),Set.of("A"),p->{throw ApiException.forbidden("无权访问目标");})).getCode());
        assertEquals(1,forbidden.planningPrompts.size());assertTrue(forbidden.reviewPrompts.isEmpty());
    }
    @Test void invalidReviewIsNotAnApprovalAndMustQuoteCurrentUserEvidence() {
        var model=new Model(HELP,HELP,HELP);model.reviews.add("{\"approved\":true}");model.reviews.add("{\"approved\":true,\"issues\":[{\"evidence\":\"本轮\",\"reason\":\"矛盾\"}]}");
        model.reject("来自旧消息","不属于本轮依据");
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(model,new AgentProperties()).plan("介绍功能",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(3,model.reviewPrompts.size());
    }
    @Test void businessReadFailureIsRepairedBeforeAnyTaskIsReturned() {
        var broad=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of());
        var one=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("docNo","EQ",List.of("D1"))))));
        var model=new Model(JsonUtil.toJson(read(broad,false)),JsonUtil.toJson(read(one,false)));var reads=new java.util.concurrent.atomic.AtomicInteger();
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan("看D1详情",new DialogueState(),List.of(),Set.of("A"),p->{
            reads.incrementAndGet();if(p.query().conditions().isEmpty())throw new ApiException(422,"匹配多条，必须唯一定位");
        });
        assertEquals(one,result.query());assertEquals(2,reads.get());assertEquals(1,model.reviewPrompts.size());
    }
    @Test void omittedCompanyNamedReportAndIndependentTopicAreSemanticReviewConcerns() {
        var broad=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(),null,List.of(),null,false,1,20,null);
        var scoped=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of());
        repair(read(broad,false),read(scoped,false),"A公司的销售全部列出来","遗漏公司和具体报表",new DialogueState());
        var state=new DialogueState();state.setBusinessQuery(scoped);
        var order=query(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.LIST,List.of());
        var noOldReport=new BusinessQuery(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.LIST,List.of(),null,List.of(),null,false,1,20,null);
        repair(read(order,false),read(noOldReport,false),"销售先不看了，改看工单","独立话题不继承旧报表",state);
        var refusal=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"当前没有该公司权限");
        repair(read(scoped,false),refusal,"也加上C公司的","不能丢弃请求的公司",state);
    }
    @Test void reviewRejectsBrokenAndOrAndPreservesEveryCondition() {
        var a=new BusinessQuery.Filter("amount","GTE",List.of("25"));var b=new BusinessQuery.Filter("amount","LTE",List.of("75"));
        var split=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(a)),new BusinessQuery.Group(List.of(b))));
        var bounded=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(a,b))));
        repair(read(split,false),read(bounded,false),"金额在25到75之间","区间上下界须同时满足",new DialogueState());
        var owner=new BusinessQuery.Filter("createdByMe","EQ",List.of("true"));var status=new BusinessQuery.Filter("planStatus","EQ",List.of("已取消"));
        repair(read(query(BusinessQuery.Domain.DISPATCH,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(owner)),new BusinessQuery.Group(List.of(status)))),false),
                read(query(BusinessQuery.Domain.DISPATCH,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(owner,status)))),false),
                "本人创建且已取消的派单","归属与状态不能拆成并集",new DialogueState());
    }
    @Test void negativeSelectionAndImplicitActionCannotProduceAnAdditionalPlan() {
        var state=new DialogueState();state.setPreviewId("p");state.setAssistantFocus("DISPATCH");
        String message="交通先缓一缓，其他照办";
        var change=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("交通"),message,List.of(),SelectorKind.DESCRIPTION,Quantifier.ALL);
        repair(dispatch(state,message,Action.PREPARE_DISPATCH,change),dispatch(state,message,Action.PREVIEW,change),message,"只调整选择，未要求新清单",state);
        var wrong=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()),false);
        repair(wrong,dispatch(state,message,Action.PREVIEW,change),message,"当前候选选择不能转为新的正向查询",state);
        String noPlan="不用准备清单，只查询销售数据";
        repair(dispatch(state,noPlan,Action.PREPARE_DISPATCH),wrong,noPlan,"准备动作被明确否定",state);
    }
    @Test void semanticRepairCannotDowngradeAnExplicitPreparationIntoPreview() {
        var state=new DialogueState();state.setPreviewId("p");String message="给当前选中的整理一份待确认清单";
        repair(dispatch(state,message,Action.PREVIEW),dispatch(state,message,Action.PREPARE_DISPATCH),message,"不能把准备清单降为预览",state);
    }
    @Test void keepOnlyAndSingleRecordMeaningAreReviewedWithoutPhraseWhitelist() {
        var state=new DialogueState();state.setPreviewId("p");String message="把设备保留下来，其余选择维持原样";
        var wrong=new ScopeChange(Target.RECORDS,Operation.KEEP_ONLY,List.of("设备"),message,List.of(),SelectorKind.DESCRIPTION,Quantifier.ALL);
        var right=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of("设备"),message,List.of(),SelectorKind.DESCRIPTION,Quantifier.ALL);
        repair(dispatch(state,message,Action.PREVIEW,wrong),dispatch(state,message,Action.PREVIEW,right),message,"保留匹配不授权排除其余",state);
        String single="把设备这一笔排除";
        repair(dispatch(state,single,Action.PREVIEW,new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("设备"),single,List.of(),SelectorKind.DESCRIPTION,Quantifier.ALL)),
                dispatch(state,single,Action.PREVIEW,new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("设备"),single,List.of(),SelectorKind.DESCRIPTION,Quantifier.ONE)),
                single,"单筆请求不能扩大到全部匹配",state);
    }
    @Test void statusRefinementCannotDropDateWithoutGroundedRemoval() {
        var date=new BusinessQuery.Filter("date","GTE",List.of("2026-01-01"));var status=new BusinessQuery.Filter("status","EQ",List.of("未派单"));
        var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(date)))));
        var next=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(status))));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("只要未派单的",state,read(next,true)));
        var removal=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,next,true,null,List.of(new AssistantPlan.FilterRemoval("date","取消日期限制")));
        assertDoesNotThrow(()->AssistantRouteGuard.validate("取消日期限制，只要未派单的",state,removal));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("只要未派单的",state,removal));
    }
    @Test void emptyFollowUpKeepsDomainAndUnresolvedContextCannotBeReferenced() {
        var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.DISPATCH,BusinessQuery.View.LIST,List.of()));
        var changed=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.SUMMARY,List.of()),true);
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("归类统计",state,changed));
        assertDoesNotThrow(()->AssistantRouteGuard.validate("另外查询报表",state,read(changed.query(),false)));
        state.setBusinessUnresolved(true);assertThrows(ApiException.class,()->AssistantRouteGuard.validate("下一页",state,read(state.getBusinessQuery().atPage(2),true)));
    }
    @Test void sortedDetailAndEmptyGroupsCannotHideAnInvalidTarget() {
        var sorted=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of("r"),"A",List.of(),"amount",true,1,1,null);
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("展开最高的那笔",new DialogueState(),read(sorted,false)));
        assertThrows(ApiException.class,()->query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of()))));
    }
    @Test void previousSensitiveIdentityIsConsistentInBothCallsAndRestoredOnlyAtBoundary() {
        String identity="900000000000000001";var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));
        state.setBusinessReferences(List.of(Map.of("recordId",identity,"reportId","r","companyCode","A")));
        var observed=new ArrayList<Prompt>();
        ChatModel model=new ChatModel(){
            @Override public ChatOptions getDefaultOptions(){return ChatOptions.builder().model("test-only").build();}
            @Override public ChatResponse call(Prompt prompt){
                observed.add(prompt);assertFalse(prompt.getUserMessage().getText().contains(identity));
                var input=JsonUtil.toMap(prompt.getUserMessage().getText());String answer;
                if(input.containsKey("proposedPlan"))answer=APPROVED;
                else {
                    String token=((Map<?,?>)((List<?>)input.get("previousRows")).get(0)).get("recordId").toString();
                    answer=JsonUtil.toJson(read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of(token)))))),true));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
            }
        };
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan("查看第一条详情",state,List.of(),Set.of("A"));
        assertEquals(identity,result.query().conditions().get(0).allOf().get(0).values().get(0));assertEquals(2,observed.size());
        for(var prompt:observed) {
            var options=(org.springframework.ai.openai.OpenAiChatOptions)prompt.getOptions();
            assertFalse(options.getInternalToolExecutionEnabled());assertTrue(options.getToolCallbacks().isEmpty());assertNotNull(options.getResponseFormat());
        }
    }
    @Test void actualHttpRequestsUseTwoNativeSchemasAndDisabledThinkingWithoutTools() throws Exception {
        var requests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            var req=JsonUtil.MAPPER.readTree(exchange.getRequestBody());requests.add(req);
            String content=req.at("/response_format/json_schema/schema/properties/approved").isMissingNode()?HELP:APPROVED;
            byte[] body=JsonUtil.toJson(Map.of("id","local","object","chat.completion","created",1,"model","test","choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",content))))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var api=org.springframework.ai.openai.api.OpenAiApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).apiKey("local-test").build();
            var model=org.springframework.ai.openai.OpenAiChatModel.builder().openAiApi(api).defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build()).build();
            assertEquals(AssistantPlan.Route.HELP,new ModelAssistantPlanner(model,new AgentProperties()).plan("你能做什么",new DialogueState(),List.of(),Set.of("A")).route());
            assertEquals(2,requests.size());
            for(var req:requests) {
                assertEquals("json_schema",req.at("/response_format/type").asText());assertTrue(req.at("/response_format/json_schema/strict").asBoolean());
                assertEquals("disabled",req.at("/thinking/type").asText());assertTrue(req.path("tools").isMissingNode() || req.path("tools").isEmpty());
            }
            assertFalse(requests.get(0).at("/response_format/json_schema/schema/properties/dispatch").isMissingNode());
            assertFalse(requests.get(1).at("/response_format/json_schema/schema/properties/approved").isMissingNode());
        } finally {server.stop(0);}
    }
}
