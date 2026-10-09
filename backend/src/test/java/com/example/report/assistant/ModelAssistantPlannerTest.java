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
    static String review(AssistantPlan expected,String evidence,String meaning) {
        return JsonUtil.toJson(new SemanticReview(Arrays.stream(SemanticReview.Aspect.values())
                .map(aspect->new SemanticReview.Requirement(aspect,evidence,meaning)).toList(),expected));
    }

    /** 分别模拟规划与复核响应，防止把第二次调用当成另一份执行计划；每次请求均留作断言。 */
    static class Model implements ChatModel {
        final List<Prompt> prompts=new ArrayList<>(),planningPrompts=new ArrayList<>(),reviewPrompts=new ArrayList<>();
        final Deque<String> replies=new ArrayDeque<>(),reviews=new ArrayDeque<>();
        String lastDraft;
        Model(String... replies){this.replies.addAll(List.of(replies));}
        Model expect(AssistantPlan expected,String evidence,String reason){reviews.add(review(expected,evidence,reason));return this;}
        @Override public ChatOptions getDefaultOptions(){return ChatOptions.builder().model("test-only").build();}
        @Override public ChatResponse call(Prompt prompt){
            prompts.add(prompt);boolean review="INDEPENDENT_EXPECTATION".equals(JsonUtil.toMap(prompt.getUserMessage().getText()).get("taskStage"));
            (review?reviewPrompts:planningPrompts).add(prompt);
            var input=JsonUtil.toMap(prompt.getUserMessage().getText());
            // 测试默认期望由替身内部预设；生产复核请求中不存在草稿，不能依赖从请求复制proposedPlan。
            String content=review?(reviews.isEmpty()?ModelAssistantPlannerTest.review(AssistantCodec.plan(lastDraft),input.get("message").toString(),"完整保留本轮要求"):reviews.removeFirst()):replies.removeFirst();
            if(!review)lastDraft=content;
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
        var model=new Model(JsonUtil.toJson(before),JsonUtil.toJson(after)).expect(after,message,reason);
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"));
        assertEquals(after,result);assertEquals(2,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());
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
            assertTrue(input.at("/dispatchCapabilities/selectionDefaults").isMissingNode());
            for(int i=0;i<reports.size();i++)assertEquals(Objects.toString(reports.get(i).ref().description(),""),input.at("/reports/"+i+"/description").asText());
            assertTrue(input.path("recentConversation").toString().contains("服务器"));assertEquals(1,input.at("/dispatchSelection/selectedCount").asInt());
        }
        var independent=JsonUtil.MAPPER.readTree(model.reviewPrompts.get(0).getUserMessage().getText());
        assertFalse(independent.has("readEvidence"));assertFalse(independent.has("proposedPlan"));
        assertEquals(0,independent.at("/recentConversation/0/messageIndex").asInt(-1));
    }
    @Test void onlyStructuralAndSemanticFailuresUseTwoBoundedRepairs() {
        var invalid=new Model("{}","{}","{}");
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(invalid,new AgentProperties()).plan("查询数据",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(3,invalid.planningPrompts.size());assertEquals(0,invalid.reviewPrompts.size());
        var wanted=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()),false);
        var denied=new Model(HELP,HELP,HELP).expect(wanted,"查询数据","动作不符").expect(wanted,"查询数据","仍遗漏查询").expect(wanted,"查询数据","仍未落实动作");
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(denied,new AgentProperties()).plan("查询数据",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(3,denied.planningPrompts.size());assertEquals(1,denied.reviewPrompts.size());
        var forbidden=new Model(HELP);
        assertEquals(403,assertThrows(ApiException.class,()->new ModelAssistantPlanner(forbidden,new AgentProperties()).plan("查询数据",new DialogueState(),List.of(),Set.of("A"),p->{throw ApiException.forbidden("无权访问目标");})).getCode());
        assertEquals(1,forbidden.planningPrompts.size());assertTrue(forbidden.reviewPrompts.isEmpty());
    }
    @Test void invalidReviewIsNotAnApprovalAndMustQuoteCurrentUserEvidence() {
        var model=new Model(HELP);model.reviews.add("{\"requirements\":[]}");model.reviews.add("{\"requirements\":[],\"expectedPlan\":null}");
        model.expect(AssistantCodec.plan(HELP),"来自旧消息","不属于本轮依据");
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(model,new AgentProperties()).plan("介绍功能",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(3,model.reviewPrompts.size());
        assertEquals(1,model.planningPrompts.size());
    }
    @Test void jsonNullIsARepairableContractErrorForBothStages() {
        assertEquals(422,assertThrows(ApiException.class,()->AssistantCodec.plan("null")).getCode());
        assertEquals(422,assertThrows(ApiException.class,()->AssistantCodec.review("null")).getCode());
        assertEquals(422,assertThrows(ApiException.class,()->AssistantCodec.query(null)).getCode());
        var model=new Model("null",HELP,HELP);model.reviews.add("null");
        assertEquals(AssistantPlan.Route.HELP,new ModelAssistantPlanner(model,new AgentProperties()).plan("介绍功能",new DialogueState(),List.of(),Set.of("A")).route());
        assertEquals(2,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={403,409,502,503})
    void businessFailureCannotTriggerAReplacementTarget(int status) {
        var query=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.ELIGIBILITY,
                List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of("1"))))));
        var model=new Model(JsonUtil.toJson(read(query,false)),HELP);
        assertEquals(status,assertThrows(ApiException.class,()->new ModelAssistantPlanner(model,new AgentProperties()).plan(
                "核验记录1",new DialogueState(),List.of(),Set.of("A"),p->{throw new ApiException(status,"当前业务核验失败");})).getCode());
        assertEquals(1,model.planningPrompts.size());assertTrue(model.reviewPrompts.isEmpty());
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
    @Test void invalidReviewRetriesTheSameValidatedDraftWithoutAnotherBusinessRead() throws Exception {
        var model=new Model(HELP);model.reviews.add("{\"approved\":true,\"issues\":[]}");
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan("介绍功能",new DialogueState(),List.of(),Set.of("A"),p->reads.incrementAndGet());
        assertEquals(AssistantPlan.Route.HELP,result.route());assertEquals(1,reads.get());assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        var retry=JsonUtil.MAPPER.readTree(model.reviewPrompts.get(1).getUserMessage().getText());
        assertFalse(retry.path("reviewValidationError").asText().isBlank());assertFalse(retry.has("proposedPlan"));assertFalse(retry.has("attemptHistory"));
    }
    @Test void reviewedClarificationCanImproveWordingWithoutReplanningBusiness() {
        var draft=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"有多条，请指明一条");
        var expected=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"当前匹配多条，请提供要核验的单据号");
        var model=new Model(JsonUtil.toJson(draft)).expect(expected,"核验这条","目标多义，需要补充身份");
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan("核验这条",new DialogueState(),List.of(),Set.of("A"));
        assertEquals(expected,result);assertEquals(1,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());
    }
    @Test void allRepairsRetainFrozenIndependentRequirementsAndEarlierReadEvidence() throws Exception {
        var scoped=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("productName","EQ",List.of("设备"))))));
        var wanted=read(scoped,false);String message="查询设备的销售数据";
        var model=new Model(HELP,JsonUtil.toJson(read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()),false)),JsonUtil.toJson(wanted))
                .expect(wanted,message,"查询动作与产品条件缺失").expect(wanted,message,"保留原动作并补充产品限定");
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A"),
                new AssistantPlanningContext(List.of(),Map.of(),p->Map.of("independentFact","已授权只读事实")));
        assertEquals(wanted,result);assertEquals(3,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());
        for(var prompt:List.of(model.planningPrompts.get(2))) {
            var attempts=JsonUtil.MAPPER.readTree(prompt.getUserMessage().getText()).path("attemptHistory");
            assertEquals(2,attempts.size());assertEquals("已授权只读事实",attempts.get(0).at("/readEvidence/independentFact").asText());
            assertTrue(attempts.get(0).path("review").toString().contains("查询动作与产品条件缺失"));
            assertFalse(attempts.get(1).path("differences").isEmpty());
        }
        var independent=JsonUtil.MAPPER.readTree(model.reviewPrompts.get(0).getUserMessage().getText());
        assertFalse(independent.has("proposedPlan"));assertFalse(independent.has("readEvidence"));assertFalse(independent.has("attemptHistory"));
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"客户甲的那一笔不要","客户甲其中一条取消","客户甲的一张发票不要","exclude one invoice of 客户甲"})
    void singularReferenceRepairsAllToOneAtTheSemanticReviewBoundary(String message) {
        var state=new DialogueState();state.setPreviewId("p");
        var all=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("客户甲"),message,List.of(),SelectorKind.COUNTERPARTY,Quantifier.ALL);
        var one=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,all.mentions(),message,List.of(),SelectorKind.COUNTERPARTY,Quantifier.ONE);
        repair(dispatch(state,message,Action.PREVIEW,all),dispatch(state,message,Action.PREVIEW,one),message,"单笔要求不能扩大到全部客户记录",state);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"金额大于10元的保留,amount,GT,10","日期是2026-10-01的选上,date,EQ,2026-10-01","名称包含设备的保留,name,CONTAINS,设备"})
    void ordinaryInclusionRepairsKeepOnlyToRestore(String message,String field,Comparison operator,String value) {
        var state=new DialogueState();state.setPreviewId("p");
        var conditions=List.of(new ConditionGroup(List.of(new FieldCondition(field,operator,List.of(value),message))));
        var wrong=new ScopeChange(Target.RECORDS,Operation.KEEP_ONLY,List.of(),message,List.of(),SelectorKind.FIELDS,Quantifier.ALL,conditions);
        var right=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of(),message,List.of(),SelectorKind.FIELDS,Quantifier.ALL,conditions);
        repair(dispatch(state,message,Action.PREVIEW,wrong),dispatch(state,message,Action.PREVIEW,right),message,"恢复匹配选择不授权排除其余记录",state);
    }
    @Test void recordQualifierCannotSilentlyReplaceExistingReportScope() {
        var state=new DialogueState();state.setPreviewId("p");String message="应收报表排除某客户的全部记录";
        var records=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("某客户"),message,List.of("应收报表"),SelectorKind.COUNTERPARTY,Quantifier.ALL);
        var invented=new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("应收报表"),message);
        repair(dispatch(state,message,Action.PREVIEW,invented,records),dispatch(state,message,Action.PREVIEW,records),message,"记录的报表限定不授权替换原范围",state);
    }
    @Test void unresolvedRequestNeedsReviewedIntentButDoesNotEraseExplicitTarget() {
        var state=new DialogueState();state.setPreviewId("p");state.setUnresolvedRequest(true);
        var clarification=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请明确是否继续使用原候选以及如何处理未解决条件");
        repair(dispatch(state,"照刚才说的做",Action.PREPARE_DISPATCH),clarification,"照刚才说的做","上一请求未完成，不能默认丢弃未解决条件",state);
        String explicit="忽略刚才未能支持的条件，为原候选生成待确认清单";
        var requested=dispatch(state,explicit,Action.PREPARE_DISPATCH);
        repair(clarification,requested,explicit,"已明确恢复原候选并要求建单，历史失败不应阻断",state);
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
                {
                    String token=((Map<?,?>)((List<?>)input.get("previousRows")).get(0)).get("recordId").toString();
                    answer=JsonUtil.toJson(read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of(token)))))),true));
                    if("INDEPENDENT_EXPECTATION".equals(input.get("taskStage")))answer=review(AssistantCodec.plan(answer),input.get("message").toString(),"保持本轮详情身份");
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
    @Test void unifiedValidationReportsExactUngroundedReportFieldBeforeRepair() {
        String message="把设备那条恢复勾选";var state=new DialogueState();state.setPreviewId("p");
        var wrong=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of("设备"),message,List.of("历史报表"),SelectorKind.DESCRIPTION,Quantifier.ONE);
        var correct=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of("设备"),message,List.of(),SelectorKind.DESCRIPTION,Quantifier.ONE);
        var model=new Model(JsonUtil.toJson(dispatch(state,message,Action.PREVIEW,wrong)),JsonUtil.toJson(dispatch(state,message,Action.PREVIEW,correct)));
        assertEquals(dispatch(state,message,Action.PREVIEW,correct),new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        String feedback=model.planningPrompts.get(1).getUserMessage().getText();
        assertTrue(feedback.contains("scopeChanges[0].reportMentions"));assertTrue(feedback.contains("未提报表时为空数组"));
    }
    @Test void candidateReferenceInTheWrongProtocolFieldGetsSpecificRepairFeedback() {
        String message="恢复刚才那条";var state=new DialogueState();state.setPreviewId("p");
        String reference="ref_"+"a".repeat(32);
        var change=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of(reference),message,List.of(),SelectorKind.REFERENCE,Quantifier.ONE);
        var correct=dispatch(state,message,Action.PREVIEW,change);
        var wrong=JsonUtil.MAPPER.valueToTree(correct);((com.fasterxml.jackson.databind.node.ArrayNode)wrong.at("/dispatch/referenceKeys")).add(reference);
        var model=new Model(wrong.toString(),JsonUtil.toJson(correct));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        String feedback=model.planningPrompts.get(1).getUserMessage().getText();
        assertTrue(feedback.contains("dispatch.referenceKeys"));assertTrue(feedback.contains("mentions"));
    }
    @Test void reportRoleAliasesMustQuoteTheActualPhraseAndCanBeRepaired() {
        String message="查看销售的候选";var state=new DialogueState();state.setPreviewId("p");
        var change=new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("销售"),message);
        var correct=dispatch(state,message,Action.PREVIEW,change);
        var wrong=AssistantPlan.dispatch(new DispatchDirective(new SemanticIntent(1,Action.PREVIEW,List.of(change),List.of(),
                List.of(new ReportConstraint("销售报表",ReportRole.INCLUDED,message)),List.of(),Clarify.NONE),
                DispatchDirective.Source.PREVIEW,AssistantReferences.previewRef(state),List.of(),message));
        var model=new Model(JsonUtil.toJson(wrong),JsonUtil.toJson(correct));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertTrue(model.planningPrompts.get(1).getUserMessage().getText().contains("reportConstraints[0].mention"));
    }
    @Test void explicitFinalCountCannotUseAnIncompleteSelectionOrChangeTargetsSilently() {
        String message="选中的两条准备清单";var state=new DialogueState();state.setPreviewId("p");
        var prepare=dispatch(state,message,Action.PREPARE_DISPATCH);
        var clarify=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"当前只有一条选中，请先确认或完成两条的选择");
        var model=new Model(JsonUtil.toJson(prepare),JsonUtil.toJson(clarify));
        var requirements=Arrays.stream(SemanticReview.Aspect.values()).map(a->new SemanticReview.Requirement(a,message,"用户要求选中的两条，不能缩减")).toList();
        model.reviews.add(JsonUtil.toJson(new SemanticReview(requirements,prepare,new SemanticReview.TargetCount(2,"两条"))));
        model.reviews.add(JsonUtil.toJson(new SemanticReview(requirements,clarify,new SemanticReview.TargetCount(2,"两条"))));
        var context=new AssistantPlanningContext(List.of(),Map.of(),p->Map.of("selectionAfter",Map.of("selectedCount",1)));
        assertEquals(clarify,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"),context));
        assertEquals(2,model.reviewPrompts.size());assertEquals(2,model.planningPrompts.size());
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("当前完整预检为 1 条"));
    }
    @Test void persistedCurrentMessageHasOneEvidencePositionWithoutRenumberingOlderDuplicates() throws Exception {
        String message="继续查看";var model=new Model(HELP);
        var history=List.of(Map.of("role","user","content",message),Map.of("role","assistant","content","此前的答复"),Map.of("role","user","content",message));
        var context=new AssistantPlanningContext(history,Map.of(),p->Map.of());
        new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A"),context);
        for(var prompt:model.prompts) {
            var input=JsonUtil.MAPPER.readTree(prompt.getUserMessage().getText());
            assertEquals(-1,input.path("messageIndex").asInt());assertEquals(2,input.path("recentConversation").size());
            assertEquals(0,input.at("/recentConversation/0/messageIndex").asInt());assertEquals(message,input.at("/recentConversation/0/content").asText());
            assertEquals(1,input.at("/recentConversation/1/messageIndex").asInt());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void actualHttpRequestsUseTwoNativeSchemasAndConfiguredThinkingWithoutTools(boolean thinking) throws Exception {
        var requests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            var req=JsonUtil.MAPPER.readTree(exchange.getRequestBody());requests.add(req);
            String content=req.at("/response_format/json_schema/schema/properties/expectedPlan").isMissingNode()?HELP:review(AssistantCodec.plan(HELP),"你能做什么","询问能力");
            byte[] body=JsonUtil.toJson(Map.of("id","local","object","chat.completion","created",1,"model","test","choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",content))))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var api=org.springframework.ai.openai.api.OpenAiApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).apiKey("local-test").build();
            var model=org.springframework.ai.openai.OpenAiChatModel.builder().openAiApi(api).defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build()).build();
            var props=new AgentProperties();props.getSemantic().setThinkingEnabled(thinking);
            assertEquals(AssistantPlan.Route.HELP,new ModelAssistantPlanner(model,props).plan("你能做什么",new DialogueState(),List.of(),Set.of("A")).route());
            assertEquals(2,requests.size());
            for(var req:requests) {
                assertEquals("json_schema",req.at("/response_format/type").asText());assertTrue(req.at("/response_format/json_schema/strict").asBoolean());
                assertEquals(thinking?"enabled":"disabled",req.at("/thinking/type").asText());
                assertEquals(thinking?8192:4096,req.path("max_tokens").asInt());
                assertTrue(req.path("tools").isMissingNode() || req.path("tools").isEmpty());
            }
            assertFalse(requests.get(0).at("/response_format/json_schema/schema/properties/dispatch").isMissingNode());
            assertFalse(requests.get(1).at("/response_format/json_schema/schema/properties/expectedPlan").isMissingNode());
        } finally {server.stop(0);}
    }
}
