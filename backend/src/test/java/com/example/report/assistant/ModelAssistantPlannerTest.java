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
        return review(expected,evidence,meaning,List.of());
    }
    static String review(AssistantPlan expected,String evidence,String meaning,List<SemanticReview.PriorConditionChange> changes) {
        return JsonUtil.toJson(new SemanticReview(Arrays.stream(SemanticReview.Aspect.values())
                .map(aspect->new SemanticReview.Requirement(aspect,evidence,meaning)).toList(),
                TaskConditionEvidence.conditions(expected).values().stream().map(node->new SemanticReview.ConditionCheck(TaskConditionEvidence.filter(node),
                        SemanticReview.ConditionOrigin.CURRENT_REQUEST,evidence,"")).toList(),changes,expected,null));
    }

    /** 分别模拟规划与复核响应，防止把第二次调用当成另一份执行计划；每次请求均留作断言。 */
    static class Model implements ChatModel {
        final List<Prompt> prompts=new ArrayList<>(),planningPrompts=new ArrayList<>(),reviewPrompts=new ArrayList<>(),purposePrompts=new ArrayList<>();
        final Deque<String> replies=new ArrayDeque<>(),reviews=new ArrayDeque<>(),purposes=new ArrayDeque<>();
        String lastDraft;
        Model(String... replies){this.replies.addAll(List.of(replies));}
        Model expect(AssistantPlan expected,String evidence,String reason){reviews.add(review(expected,evidence,reason));return this;}
        Model expectChanged(AssistantPlan expected,String evidence,String reason,SemanticReview.PriorConditionChange... changes){reviews.add(review(expected,evidence,reason,List.of(changes)));return this;}
        @Override public ChatOptions getDefaultOptions(){return ChatOptions.builder().model("test-only").build();}
        @Override public ChatResponse call(Prompt prompt){
            prompts.add(prompt);var request=JsonUtil.toMap(prompt.getUserMessage().getText());
            if("TASK_PURPOSE".equals(request.get("taskStage"))) {
                purposePrompts.add(prompt);AssistantPlan target=null;SemanticReview.TargetCount count=null;
                if(target==null && !reviews.isEmpty())try{var expected=AssistantCodec.review(reviews.peekFirst());target=expected.expectedPlan();count=expected.targetCount();}catch(ApiException ignored) { }
                // 独立目标响应来自测试预设，不从生产请求读取草稿；结构失败测试可没有任何可解析计划。
                if(target==null)for(String scripted:replies)try{target=AssistantCodec.plan(scripted);break;}catch(ApiException ignored) { }
                if(target==null)target=AssistantCodec.plan(HELP);
                var queryContext=target.route()!=AssistantPlan.Route.BUSINESS_QUERY?TaskPurpose.QueryContext.NOT_QUERY:
                        target.followUp()?TaskPurpose.QueryContext.FOLLOW_UP:TaskPurpose.QueryContext.INDEPENDENT;
                String content=purposes.isEmpty()?JsonUtil.toJson(new TaskPurpose(TaskPurpose.action(target),queryContext,request.get("message").toString(),count,target.query()==null?null:target.query().domain())):purposes.removeFirst();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
            }
            boolean review="INDEPENDENT_EXPECTATION".equals(request.get("taskStage"));
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
        var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("BUSINESS_QUERY");
        state.setBusinessReferences(List.of(Map.of("recordId","1"),Map.of("recordId","2")));
        for(Long count:Arrays.asList(null,2L,20L)) {
            state.setBusinessTotalCount(count);var model=new Model(HELP);
            new ModelAssistantPlanner(model,new AgentProperties()).plan("如何选择",state,List.of(),Set.of("A"));
            var context=JsonUtil.MAPPER.readTree(model.planningPrompts.get(0).getUserMessage().getText());
            assertEquals(Objects.equals(count,2L),context.at("/previousResult/allMatchesDisplayed").asBoolean());
        }
        state.setBusinessTotalCount(2L);state.setBusinessUnresolved(true);assertFalse(AssistantReferences.complete(state));
        state.setBusinessUnresolved(false);state.setBusinessQuery(state.getBusinessQuery().atPage(2));state.setAssistantFocus("BUSINESS_QUERY");assertFalse(AssistantReferences.complete(state));
    }
    @Test void currentCapabilitiesConversationAndSelectionReachBothStages() throws Exception {
        var model=new Model(HELP);var reports=new com.example.report.support.TestCatalog().entries();
        var context=new AssistantPlanningContext(List.of(Map.of("role","user","content","之前保留了服务器")),Map.of("selectedCount",1),p->Map.of("checked",true));
        new ModelAssistantPlanner(model,new AgentProperties()).plan("能怎么处理",new DialogueState(),reports,Set.of("A"),context);
        for(var prompt:java.util.stream.Stream.concat(model.planningPrompts.stream(),model.reviewPrompts.stream()).toList()) {
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
        assertEquals(3,model.reviewPrompts.size());assertEquals(1,model.purposePrompts.size());
        assertEquals(1,model.planningPrompts.size());
    }
    /** 复核误解明确等号时先修复复核本身，不把正确草稿改成相同错误。 */
    @Test void wrongInclusiveBoundaryInReviewCannotCorruptACorrectDraft() {
        String message="查询费用，三千二百元以上";
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GTE",List.of("3200")))))),false);
        var wrong=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("3200")))))),false);
        var model=new Model(JsonUtil.toJson(correct)).expect(wrong,message,"误把以上当作严格大于").expect(correct,message,"以上包含等于");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("明确数值比较证据要求 GTE"));
    }
    @Test void dispatchReviewKeepsTheBoundaryOfTheExcludedSet() {
        String message="金额96000元以上的全部取消选择。";var state=new DialogueState();state.setPreviewId("numeric-preview");
        var correct=dispatch(state,message,Action.PREVIEW,new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),message,List.of(),SelectorKind.FIELDS,Quantifier.ALL,
                List.of(new ConditionGroup(List.of(new FieldCondition("amount",Comparison.GTE,List.of("96000"),"金额96000元以上"))))));
        var wrong=dispatch(state,message,Action.PREVIEW,new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),message,List.of(),SelectorKind.FIELDS,Quantifier.ALL,
                List.of(new ConditionGroup(List.of(new FieldCondition("amount",Comparison.GT,List.of("96000"),"金额96000元以上"))))));
        var model=new Model(JsonUtil.toJson(correct)).expect(wrong,message,"错误漏掉等于").expect(correct,message,"排除的匹配集合包含等于");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
    }
    @Test void boundaryQualifierAndPredicateNegationAreRepairedWithoutChangingTheCorrectQuery() {
        String message="改为3200元以下，但不含3200本身。";var state=new DialogueState();
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","LTE",List.of("3200")))))));state.setAssistantFocus("BUSINESS_QUERY");
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","LT",List.of("3200")))))),true);
        var redundant=new AssistantPlan(correct.route(),correct.query(),true,null,List.of(new AssistantPlan.FilterRemoval("amount",message)));
        var proof=List.of(new SemanticReview.ConditionCheck(correct.query().conditions().get(0).allOf().get(0),SemanticReview.ConditionOrigin.CURRENT_REQUEST,message,message));
        var requirements=Arrays.stream(SemanticReview.Aspect.values()).map(a->new SemanticReview.Requirement(a,message,"仅排除端点")).toList();
        var model=new Model(JsonUtil.toJson(correct));
        model.reviews.add(JsonUtil.toJson(new SemanticReview(requirements,proof,redundant,null)));
        model.expectChanged(correct,message,"不含本数不取反整个比较",new SemanticReview.PriorConditionChange(state.getBusinessQuery().conditions().get(0).allOf().get(0),message));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("移除多余撤销声明"));
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("negationEvidence必须为空"));
    }
    /** 业务前提与多余条件一次反馈，在含独立目标判断的三次预算内形成具体澄清。 */
    @Test void independentReviewRepairsPreconditionsAndStaleChecksTogether() {
        String message="把剩余候选准备成清单";var state=new DialogueState();state.setPreviewId("interrupted-preview");
        var clarification=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请先重新展示并核对候选范围");
        var unavailable=dispatch(state,message,Action.PREPARE_DISPATCH);
        var staleCheck=new SemanticReview.ConditionCheck(new BusinessQuery.Filter("amount","GTE",List.of("1000")),
                SemanticReview.ConditionOrigin.CURRENT_REQUEST,message,"");
        var model=new Model(JsonUtil.toJson(clarification));
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.PREPARE_DISPATCH,TaskPurpose.QueryContext.NOT_QUERY,message)));
        model.reviews.add(JsonUtil.toJson(new SemanticReview(List.of(new SemanticReview.Requirement(SemanticReview.Aspect.ACTION,message,"准备当前候选")),List.of(staleCheck),unavailable,null)));
        model.expect(clarification,message,"必须先核对候选范围");
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"),p->{
            if(p.equals(unavailable))throw new ApiException(422,"候选已被另一话题打断，需重新展示核对");
        });
        assertEquals(clarification,result);assertEquals(1,model.planningPrompts.size());
        assertEquals(1,model.purposePrompts.size());assertEquals(2,model.reviewPrompts.size());
        String feedback=model.reviewPrompts.get(1).getUserMessage().getText();
        assertTrue(feedback.contains("候选已被另一话题打断"));assertTrue(feedback.contains("conditionChecks必须为[]"));
        assertFalse(feedback.contains("proposedPlan"));assertFalse(feedback.contains("attemptHistory"));
    }
    @Test void unknownReviewFieldsAreReportedAsDeletionsWithTheAllowedObjectShape() throws Exception {
        String message="金额超过9.6万元";
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("96000")))))),false);
        var invalid=JsonUtil.MAPPER.readTree(review(correct,message,"严格大于"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.at("/conditionChecks/0")).put("meaning","放错层级的说明");
        var model=new Model(JsonUtil.toJson(correct));model.reviews.add(invalid.toString());model.expect(correct,message,"严格大于");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        var error=JsonUtil.MAPPER.readTree(model.reviewPrompts.get(1).getUserMessage().getText()).path("reviewValidationError").asText();
        assertTrue(error.contains("conditionChecks.meaning"));assertTrue(error.contains("必须删除该字段"));assertTrue(error.contains("negationEvidence"));
    }
    @Test void unresolvedQueryFeedbackAllowsAnExplicitIndependentRecoveryButNeverFollowUp() {
        String message="金额至少9.6万元，包括正好9.6万的。";var state=new DialogueState();
        var query=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GTE",List.of("96000"))))));
        state.setBusinessQuery(query);state.setAssistantFocus("BUSINESS_QUERY");state.setBusinessUnresolved(true);
        var correct=read(query,false);var inherited=read(query,true);
        var model=new Model(JsonUtil.toJson(correct)).expect(inherited,message,"误承接失败请求").expect(correct,message,"重建完整独立查询");
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.INDEPENDENT,message)));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(2,model.reviewPrompts.size());assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("followUp=false"));
    }
    @Test void reviewBusinessFailurePrecedesConditionRepairAndCannotChangeTargets() {
        String message="金额至少3200元";
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GTE",List.of("3200")))))),false);
        var denied=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("3200")))))),false);
        var model=new Model(JsonUtil.toJson(correct)).expect(denied,message,"该期望同时有边界及业务故障");
        var failure=assertThrows(ApiException.class,()->new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A"),p->{
            if(p.equals(denied))throw new ApiException(403,"当前目标不可访问");
        }));
        assertEquals(403,failure.getCode());assertEquals(1,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());
    }
    /** 历史原文虽然真实存在，也不能重新激活已从成功查询撤销的字段。 */
    @Test void reviewCannotRestoreRetiredFiltersFromOlderConversation() {
        String message="上下限都包含本数，还是680到3200元。";
        var low=new BusinessQuery.Filter("amount","GTE",List.of("680"));var high=new BusinessQuery.Filter("amount","LTE",List.of("3200"));
        var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,
                List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("680")),new BusinessQuery.Filter("amount","LT",List.of("3200")))))));state.setAssistantFocus("BUSINESS_QUERY");
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(low,high)))),true);
        var wrong=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(low,high,new BusinessQuery.Filter("expenseType","NE",List.of("办公用品")))))),true);
        var requirements=Arrays.stream(SemanticReview.Aspect.values()).map(aspect->new SemanticReview.Requirement(aspect,message,"沿用当前查询并包含端点")).toList();
        var checks=TaskConditionEvidence.conditions(wrong).entrySet().stream().map(entry->new SemanticReview.ConditionCheck(TaskConditionEvidence.filter(entry.getValue()),
                "expenseType".equals(entry.getValue().path("field").asText())?SemanticReview.ConditionOrigin.ACTIVE_QUERY:SemanticReview.ConditionOrigin.CURRENT_REQUEST,message,"")).toList();
        var model=new Model(JsonUtil.toJson(correct));model.reviews.add(JsonUtil.toJson(new SemanticReview(requirements,checks,wrong,null)));
        model.expectChanged(correct,message,"已撤销费用类型，不能重加",
                new SemanticReview.PriorConditionChange(state.getBusinessQuery().conditions().get(0).allOf().get(0),message),
                new SemanticReview.PriorConditionChange(state.getBusinessQuery().conditions().get(0).allOf().get(1),message));
        var context=new AssistantPlanningContext(List.of(Map.of("role","user","content","办公用品不要"),Map.of("role","user","content","费用类型不限制了")),Map.of(),p->Map.of());
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"),context));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("已撤销条件不能"));
    }
    @Test void independentPurposeKeepsViewingCandidatesFromBecomingPlanPreparation() throws Exception {
        String message="我准备看看能派的记录";var state=new DialogueState();state.setPreviewId("purpose-preview");
        var view=dispatch(state,message,Action.PREVIEW);var prepare=dispatch(state,message,Action.PREPARE_DISPATCH);
        var model=new Model(JsonUtil.toJson(view)).expect(prepare,message,"错误升级成准备清单").expect(view,message,"查看候选");
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.PREVIEW,TaskPurpose.QueryContext.NOT_QUERY,"看看能派的记录")));
        assertEquals(view,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());assertEquals(1,model.purposePrompts.size());
        var input=JsonUtil.MAPPER.readTree(model.purposePrompts.get(0).getUserMessage().getText());
        for(String key:List.of("attemptHistory","invalidReview","expectedPlan","proposedPlan","validationError"))assertFalse(input.has(key));
    }
    @Test void purposeOfReadOnlyRefinementDoesNotRequireACandidatePreview() {
        String message="仅保留金额小于二十元的记录";var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("BUSINESS_QUERY");
        var mistaken=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"缺少派单候选");
        var read=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","LT",List.of("20")))))),true);
        var model=new Model(JsonUtil.toJson(mistaken),JsonUtil.toJson(read)).expect(read,message,"筛选只读查询结果");
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.FOLLOW_UP,"仅保留金额小于二十元的记录")));
        assertEquals(read,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(2,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());assertEquals(1,model.purposePrompts.size());
    }
    @Test void invalidPurposeCannotBeIgnoredToApproveAnAction() {
        var clarify=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请说明对象");
        var model=new Model(JsonUtil.toJson(clarify));model.purposes.add("{}");model.purposes.add("{}");
        assertThrows(ApiException.class,()->new ModelAssistantPlanner(model,new AgentProperties()).plan("继续",new DialogueState(),List.of(),Set.of("A")));
        assertEquals(2,model.purposePrompts.size());assertTrue(model.planningPrompts.isEmpty());assertTrue(model.reviewPrompts.isEmpty());
    }
    /** 无效草稿也必须收到先前独立确定的目标，不能连续修错派单结构后才识别当前只读查询。 */
    @Test void independentPurposeReachesTheVeryFirstDraftAndAllStructuralRepairs() throws Exception {
        String message="金额大于十万元的留下看看";var state=new DialogueState();
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("BUSINESS_QUERY");
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("100000")))))),true);
        var model=new Model("{}",JsonUtil.toJson(correct));model.expect(correct,message,"只读结果上的继续筛选");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertSame(model.purposePrompts.get(0),model.prompts.get(0));
        for(var prompt:model.planningPrompts)assertEquals("BUSINESS_QUERY",JsonUtil.MAPPER.readTree(prompt.getUserMessage().getText()).at("/taskPurpose/purpose").asText());
        assertFalse(model.purposePrompts.get(0).getUserMessage().getText().contains("rejectedDraft"));
    }
    /** 被恢复对象可来自历史，但恢复授权始终属于本轮；错误来源必须在复核自身修正。 */
    @Test void restorationRequirementCannotReplaceCurrentAuthorizationWithHistory() throws Exception {
        String message="刚才设备排除错了，恢复它";var state=new DialogueState();state.setPreviewId("current-authorization");
        var correct=dispatch(state,message,Action.PREVIEW,new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of("设备"),message,List.of(),SelectorKind.DESCRIPTION,Quantifier.ONE));
        var invalid=JsonUtil.MAPPER.readTree(review(correct,message,"按本轮更正恢复"));
        for(var node:invalid.path("requirements"))if("CONDITIONS".equals(node.path("aspect").asText())) {
            ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("messageIndex",0).put("evidence","设备先排除");
        }
        var model=new Model(JsonUtil.toJson(correct));model.reviews.add(invalid.toString());model.expect(correct,message,"恢复仍由本轮授权");
        var context=new AssistantPlanningContext(List.of(Map.of("role","user","content","设备先排除")),Map.of(),p->Map.of());
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"),context));
        assertEquals(2,model.reviewPrompts.size());assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("messageIndex必须为-1"));
    }
    @Test void unavailableQueryObjectsExplainTheBusinessRecoveryWithoutInventedKeys() {
        var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.SUMMARY,List.of()));state.setAssistantFocus("BUSINESS_QUERY");
        state.setBusinessReferences(List.of(Map.of("recordId","1","reportId","r","companyCode","A")));
        var objects=AssistantReferences.queryContext(state);
        assertEquals(false,objects.get("available"));assertEquals("SUMMARY_ONLY",objects.get("unavailableReason"));
        assertEquals(List.of(),objects.get("rows"));assertNull(objects.get("sourceRef"));
        assertTrue(objects.get("recoveryHint").toString().contains("先列出业务记录"));
    }
    /** 候选已成为当前焦点时，旧查询不能让目标判断错误地承接查询；目标须先修正再交给两个详细阶段。 */
    @Test void purposeCannotFollowAHiddenQueryWhileCandidatesHaveFocus() throws Exception {
        String message="低于五百元的全部取消选择";var state=new DialogueState();state.setPreviewId("focused-preview");
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("DISPATCH");
        var change=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),message,List.of(),SelectorKind.FIELDS,Quantifier.ALL,
                List.of(new ConditionGroup(List.of(new FieldCondition("amount",Comparison.LT,List.of("500"),message)))));
        var correct=dispatch(state,message,Action.PREVIEW,change);var model=new Model(JsonUtil.toJson(correct));
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.FOLLOW_UP,message)));
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.PREVIEW,TaskPurpose.QueryContext.NOT_QUERY,message)));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(2,model.purposePrompts.size());assertEquals(1,model.reviewPrompts.size());
        for(var prompt:model.purposePrompts) {
            var input=JsonUtil.MAPPER.readTree(prompt.getUserMessage().getText());
            assertTrue(input.path("activeQuery").isNull());assertFalse(input.has("previousQuery"));
        }
        assertTrue(model.purposePrompts.get(1).getUserMessage().getText().contains("当前候选与普通查询是不同对象"));
    }
    @Test void crossDomainPurposeMustBeIndependentBeforeTheFirstDraft() throws Exception {
        String message="再看应收报表的全部记录";var state=new DialogueState();
        state.setBusinessQuery(query(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("BUSINESS_QUERY");
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()),false);
        var model=new Model(JsonUtil.toJson(correct));
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.FOLLOW_UP,message)));
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.INDEPENDENT,message)));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(2,model.purposePrompts.size());assertEquals(1,model.reviewPrompts.size());
        assertTrue(model.purposePrompts.get(1).getUserMessage().getText().contains("跨数据域必须INDEPENDENT"));
        assertEquals("INDEPENDENT",JsonUtil.MAPPER.readTree(model.planningPrompts.get(0).getUserMessage().getText()).at("/taskPurpose/queryContext").asText());
    }
    /** 重建和续查的分歧同样要独立判断，避免错误复核将仍在历史里的筛选反加到新任务。 */
    @Test void independentPurposeResolvesQueryContinuityWithinTheSharedBudget() {
        String message="从头查询产品名称包含云的数据，不加金额限制";var state=new DialogueState();
        var oldAmount=new BusinessQuery.Filter("amount","GTE",List.of("30000"));
        var oldProduct=new BusinessQuery.Filter("productName","NE",List.of("打印机"));
        var current=new BusinessQuery.Filter("productName","CONTAINS",List.of("云"));
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(oldAmount,oldProduct)))));state.setAssistantFocus("BUSINESS_QUERY");
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(current)))),false);
        var invalidQuery=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(oldAmount,oldProduct,current))));
        var wrong=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,invalidQuery,true,null,List.of(new AssistantPlan.FilterRemoval("amount",message)));
        var model=new Model(JsonUtil.toJson(correct)).expect(wrong,message,"错误继承旧任务").expect(correct,message,"新任务没有旧条件");
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.INDEPENDENT,message)));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());assertEquals(1,model.purposePrompts.size());
        String feedback=model.reviewPrompts.get(1).getUserMessage().getText();
        assertTrue(feedback.contains("queryContext=INDEPENDENT"));assertTrue(feedback.contains("移除多余撤销声明"));
        assertFalse(model.purposePrompts.get(0).getUserMessage().getText().contains("expectedPlan"));
    }
    @Test void independentPurposeDoesNotTurnARefinementIntoANewQuery() {
        String message="继续这批数据，金额至少50元";var state=new DialogueState();
        var product=new BusinessQuery.Filter("productName","EQ",List.of("设备"));var amount=new BusinessQuery.Filter("amount","GTE",List.of("50"));
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(product)))));state.setAssistantFocus("BUSINESS_QUERY");
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(product,amount)))),true);
        var wrong=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(amount)))),false);
        var model=new Model(JsonUtil.toJson(correct)).expect(wrong,message,"错误丢弃当前范围").expect(correct,message,"继承仍有效的条件");
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.FOLLOW_UP,message)));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(2,model.reviewPrompts.size());assertEquals(1,model.purposePrompts.size());
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("queryContext=FOLLOW_UP"));
    }
    @Test void actionProhibitionDifferencesUseActionEvidenceWithoutInventingDataFilters() {
        String message="把留下的做成清单，先不要提交。";var state=new DialogueState();state.setPreviewId("prohibition-preview");
        var correct=dispatch(state,message,Action.PREPARE_DISPATCH);
        var prohibited=new SemanticIntent(1,Action.PREPARE_DISPATCH,List.of(),List.of(new Restriction(ForbiddenAction.PREVIEW,RestrictionScope.THIS_TURN,"先不要提交")),List.of(),List.of(),Clarify.NONE);
        var wrong=AssistantPlan.dispatch(new DispatchDirective(prohibited,DispatchDirective.Source.PREVIEW,AssistantReferences.previewRef(state),List.of(),message));
        var model=new Model(JsonUtil.toJson(wrong),JsonUtil.toJson(correct));
        model.reviews.add(JsonUtil.toJson(new SemanticReview(List.of(new SemanticReview.Requirement(SemanticReview.Aspect.ACTION,message,"只准备清单，执行仍需确认")),correct)));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(2,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());
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
        var model=new Model(JsonUtil.toJson(read(broad,false)),JsonUtil.toJson(read(one,false))).expect(read(one,false),"看D1详情","只核对明确编号的单笔");
        var reads=new java.util.concurrent.atomic.AtomicInteger();
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
        var state=new DialogueState();state.setBusinessQuery(scoped);state.setAssistantFocus("BUSINESS_QUERY");
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
        String explicit="忽略刚才未能支持的条件，恢复原候选的全部记录并生成待确认清单";
        var requested=dispatch(state,explicit,Action.PREPARE_DISPATCH,new ScopeChange(Target.RECORDS,Operation.RESTORE_ALL,List.of(),explicit));
        repair(clarification,requested,explicit,"已明确恢复原候选并要求建单，历史失败不应阻断",state);
    }
    @Test void statusRefinementCannotDropDateWithoutGroundedRemoval() {
        var date=new BusinessQuery.Filter("date","GTE",List.of("2026-01-01"));var status=new BusinessQuery.Filter("status","EQ",List.of("未派单"));
        var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(date)))));state.setAssistantFocus("BUSINESS_QUERY");
        var next=query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(status))));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("只要未派单的",state,read(next,true)));
        var removal=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,next,true,null,List.of(new AssistantPlan.FilterRemoval("date","取消日期限制")));
        assertDoesNotThrow(()->AssistantRouteGuard.validate("取消日期限制，只要未派单的",state,removal));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("只要未派单的",state,removal));
    }
    @Test void emptyFollowUpKeepsDomainAndUnresolvedContextCannotBeReferenced() {
        var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.DISPATCH,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("BUSINESS_QUERY");
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
        String identity="900000000000000001";var state=new DialogueState();state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("BUSINESS_QUERY");
        state.setBusinessReferences(List.of(Map.of("recordId",identity,"reportId","r","companyCode","A")));
        var observed=new ArrayList<Prompt>();
        ChatModel model=new ChatModel(){
            @Override public ChatOptions getDefaultOptions(){return ChatOptions.builder().model("test-only").build();}
            @Override public ChatResponse call(Prompt prompt){
                observed.add(prompt);assertFalse(prompt.getUserMessage().getText().contains(identity));
                var input=JsonUtil.toMap(prompt.getUserMessage().getText());String answer;
                if("TASK_PURPOSE".equals(input.get("taskStage")))answer=JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.FOLLOW_UP,input.get("message").toString()));
                else {
                    String token=((Map<?,?>)((List<?>)input.get("previousRows")).get(0)).get("recordId").toString();
                    answer=JsonUtil.toJson(read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of(token)))))),true));
                    if("INDEPENDENT_EXPECTATION".equals(input.get("taskStage")))answer=review(AssistantCodec.plan(answer),input.get("message").toString(),"保持本轮详情身份");
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
            }
        };
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan("查看第一条详情",state,List.of(),Set.of("A"));
        assertEquals(identity,result.query().conditions().get(0).allOf().get(0).values().get(0));assertEquals(3,observed.size());
        for(var prompt:observed) {
            var options=(org.springframework.ai.openai.OpenAiChatOptions)prompt.getOptions();
            assertFalse(options.getInternalToolExecutionEnabled());assertTrue(options.getToolCallbacks().isEmpty());assertNotNull(options.getResponseFormat());
        }
    }
    @Test void unifiedValidationReportsExactUngroundedReportFieldBeforeRepair() {
        String message="把设备那条恢复勾选";var state=new DialogueState();state.setPreviewId("p");
        var wrong=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of("设备"),message,List.of("历史报表"),SelectorKind.DESCRIPTION,Quantifier.ONE);
        var correct=new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of("设备"),message,List.of(),SelectorKind.DESCRIPTION,Quantifier.ONE);
        var model=new Model(JsonUtil.toJson(dispatch(state,message,Action.PREVIEW,wrong)),JsonUtil.toJson(dispatch(state,message,Action.PREVIEW,correct)))
                .expect(dispatch(state,message,Action.PREVIEW,correct),message,"保留当前对象，不补历史报表");
        assertEquals(dispatch(state,message,Action.PREVIEW,correct),new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        String feedback=model.planningPrompts.get(1).getUserMessage().getText();
        assertTrue(feedback.contains("scopeChanges[0].reportMentions"));assertTrue(feedback.contains("未提报表时为空数组"));
        assertTrue(feedback.contains("currentReportMentions=[]"));assertTrue(feedback.contains("字段能力校验"));
    }
    @Test void independentRestartRepairsOnlyTheInvalidRemovalDeclaration() throws Exception {
        String message="重新查销售，取消旧筛选，仅看设备";var state=new DialogueState();
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("100")))))));state.setAssistantFocus("BUSINESS_QUERY");
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("productName","EQ",List.of("设备")))))),false);
        var bad=JsonUtil.MAPPER.readTree(review(correct,message,"独立重建"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)bad.at("/expectedPlan/removedFilters")).addObject().put("field","amount").put("evidence","取消旧筛选");
        var model=new Model(JsonUtil.toJson(correct));model.reviews.add(bad.toString());model.expect(correct,message,"新查询无需声明旧字段撤销");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        String feedback=model.reviewPrompts.get(1).getUserMessage().getText();
        assertTrue(feedback.contains("removedFilters必须为[]"));assertTrue(feedback.contains("不能为了保留removedFilters"));
    }
    @Test void omittedOldBoundaryInReviewCannotExpandACorrectTwoSidedQuery() {
        String message="上限改为100，其他条件不变";var state=new DialogueState();
        var lower=new BusinessQuery.Filter("amount","GTE",List.of("20"));var upper=new BusinessQuery.Filter("amount","LTE",List.of("100"));
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(lower)))));state.setAssistantFocus("BUSINESS_QUERY");
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(lower,upper)))),true);
        var wrong=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(upper)))),true);
        var model=new Model(JsonUtil.toJson(correct)).expect(wrong,message,"错误删除下限").expect(correct,message,"保留下限，仅新增上限");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("priorConditionChanges遗漏"));
    }
    /** 并列原文省略字段名时，反馈同时定位要求和条件引用；不能迫使正确区间草稿重新解释业务。 */
    @Test void expandedSharedSubjectInEvidenceIsRepairedAtItsExactPaths() throws Exception {
        String message="金额不少于680元且不高于3200元";
        var lower=new BusinessQuery.Filter("amount","GTE",List.of("680"));var upper=new BusinessQuery.Filter("amount","LTE",List.of("3200"));
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(lower,upper)))),false);
        var wrong=JsonUtil.MAPPER.readTree(review(correct,message,"保留闭区间"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)wrong.at("/requirements/2")).put("evidence","金额不高于3200元");
        ((com.fasterxml.jackson.databind.node.ObjectNode)wrong.at("/conditionChecks/1")).put("evidence","金额不高于3200元");
        var model=new Model(JsonUtil.toJson(correct));model.reviews.add(wrong.toString());model.expect(correct,message,"引用完整原句，不补省略字段名");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        String feedback=JsonUtil.MAPPER.readTree(model.reviewPrompts.get(1).getUserMessage().getText()).path("reviewValidationError").asText();
        assertTrue(feedback.contains("requirements[2].evidence"));assertTrue(feedback.contains("conditionChecks[1].evidence"));
        assertTrue(feedback.contains("金额不高于3200元"));assertTrue(feedback.contains("省略的主语、字段名或单位不能补入"));
    }
    @Test void recoveryReconstructsAnUnfinishedQueryWithoutPretendingItSucceeded() {
        String message="上限改成50元，仍看刚才的范围";var state=new DialogueState();state.setBusinessUnresolved(true);
        var correct=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","LTE",List.of("50")))))),false);
        var purpose=new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.RECOVERY,message);
        assertThrows(ApiException.class,()->purpose.validateContext(null,false));assertDoesNotThrow(()->purpose.validateContext(null,true));
        var model=new Model(JsonUtil.toJson(correct)).expect(correct,message,"重建用户已指定范围，不扩大为全部");model.purposes.add(JsonUtil.toJson(purpose));
        var context=new AssistantPlanningContext(List.of(Map.of("role","user","content","查A公司指定报表，金额不超过80元"),Map.of("role","assistant","content","上次查询未完成")),Map.of(),p->Map.of());
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"),context);
        assertEquals(correct,result);assertFalse(result.followUp());assertTrue(result.removedFilters().isEmpty());
        assertTrue(model.reviewPrompts.get(0).getUserMessage().getText().contains("RECOVERY"));
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
        var model=new Model(JsonUtil.toJson(wrong),JsonUtil.toJson(correct)).expect(correct,message,"使用本轮报表原词");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertTrue(model.planningPrompts.get(1).getUserMessage().getText().contains("reportConstraints[0].mention"));
    }
    /** 反馈须指向被补全的实体值；修正不得删除用户原有的报表范围。 */
    @Test void reportAliasFeedbackIdentifiesTheWrongValueInBothScopeAndRecordSelectors() {
        String message="费用这部分先取消";var state=new DialogueState();state.setPreviewId("alias-preview");
        for(boolean records:List.of(false,true)) {
            var wrongChange=records
                    ?new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),message,List.of("费用报表"),SelectorKind.ALL,Quantifier.ALL)
                    :new ScopeChange(Target.REPORTS,Operation.REMOVE,List.of("费用报表"),message);
            var correctChange=records
                    ?new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(),message,List.of("费用"),SelectorKind.ALL,Quantifier.ALL)
                    :new ScopeChange(Target.REPORTS,Operation.REMOVE,List.of("费用"),message);
            var correct=dispatch(state,message,Action.PREVIEW,correctChange);
            var model=new Model(JsonUtil.toJson(dispatch(state,message,Action.PREVIEW,wrongChange)),JsonUtil.toJson(correct)).expect(correct,message,"保留正确报表原词与范围");
            assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
            String feedback=model.planningPrompts.get(1).getUserMessage().getText();
            assertTrue(feedback.contains(records?"scopeChanges[0].reportMentions[0]":"scopeChanges[0].mentions[0]"));
            assertTrue(feedback.contains("费用报表"));assertTrue(feedback.contains("简称/别名"));
            assertEquals(2,model.planningPrompts.size());
        }
    }
    /** 可解析但证据有误的草稿，必须在首次反馈同时收到完整范围差异，不能到最后一次才比较语义。 */
    @Test void firstRepairIncludesContractFailureAndIndependentScopeDifferences() throws Exception {
        String message="查看A公司费用候选";var state=new DialogueState();state.setPreviewId("joint-review");
        var wrong=dispatch(state,message,Action.PREVIEW,new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("费用报表"),message));
        var correct=dispatch(state,message,Action.PREVIEW,new ScopeChange(Target.COMPANY,Operation.REPLACE,List.of("A公司"),message),
                new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("费用"),message));
        var model=new Model(JsonUtil.toJson(wrong),JsonUtil.toJson(correct)).expect(correct,message,"范围须包含本轮明确的公司及报表");
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        var attempt=JsonUtil.MAPPER.readTree(model.planningPrompts.get(1).getUserMessage().getText()).at("/attemptHistory/0");
        assertEquals("PROGRAM_AND_SEMANTIC_VALIDATION",attempt.path("stage").asText());
        assertTrue(attempt.path("validationError").asText().contains("mentions[0]"));
        assertFalse(attempt.path("differences").isEmpty());assertFalse(attempt.path("review").isNull());
        assertEquals(2,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());
        String independent=model.reviewPrompts.get(0).getUserMessage().getText();
        assertFalse(independent.contains("rejectedDraft"));assertFalse(independent.contains("attemptHistory"));
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
    /** 目标总数独立于详细复核，复核漏填时仍一次反馈实际不足，不能生成缩水清单。 */
    @Test void omittedReviewCountCannotBypassIndependentFinalQuantity() {
        String message="选中的三条生成清单，我来确认";var state=new DialogueState();state.setPreviewId("quantity-preview");
        var prepare=dispatch(state,message,Action.PREPARE_DISPATCH);
        var clarify=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"当前仅选中两条，请先核对第三条");
        var count=new SemanticReview.TargetCount(3,"选中的三条生成清单");
        var model=new Model(JsonUtil.toJson(clarify));
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.PREPARE_DISPATCH,TaskPurpose.QueryContext.NOT_QUERY,message,count)));
        model.expect(prepare,message,"复核错误地省略总数");
        model.reviews.add(JsonUtil.toJson(new SemanticReview(List.of(new SemanticReview.Requirement(SemanticReview.Aspect.ACTION,message,"数量不足须澄清")),clarify,count)));
        var context=new AssistantPlanningContext(List.of(),Map.of(),p->Map.of("selectionAfter",Map.of("selectedCount",2)));
        assertEquals(clarify,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"),context));
        String feedback=model.reviewPrompts.get(1).getUserMessage().getText();
        assertTrue(feedback.contains("targetCount"));assertTrue(feedback.contains("当前完整预检为 2 条"));
        assertEquals(1,model.planningPrompts.size());assertEquals(3,model.purposePrompts.size()+model.reviewPrompts.size());
    }
    /** 局部操作量与最终总数是不同约束，详细复核不得擅自将前者转成后者。 */
    @Test void localRestorationQuantityDoesNotConstrainFinalSelectionSize() {
        String message="恢复设备这两条，其余保持";var state=new DialogueState();state.setPreviewId("local-quantity");
        var plan=dispatch(state,message,Action.PREVIEW,new ScopeChange(Target.RECORDS,Operation.RESTORE,List.of("设备"),message,List.of(),SelectorKind.DESCRIPTION,Quantifier.ALL));
        var model=new Model(JsonUtil.toJson(plan));
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.PREVIEW,TaskPurpose.QueryContext.NOT_QUERY,message)));
        var requirements=List.of(new SemanticReview.Requirement(SemanticReview.Aspect.ACTION,message,"仅恢复局部选择"));
        model.reviews.add(JsonUtil.toJson(new SemanticReview(requirements,plan,new SemanticReview.TargetCount(2,"两条"))));
        model.expect(plan,message,"没有限定最终总数");
        var context=new AssistantPlanningContext(List.of(),Map.of(),p->Map.of("selectionAfter",Map.of("selectedCount",5)));
        assertEquals(plan,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"),context));
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("当前完整预检为 5 条"));
    }
    /** 目标未识别数量不是取消数量约束；详细复核补充本轮总数后仍须在冻结前核对实际条数。 */
    @Test void completeReviewCanRecoverAnExplicitCountMissedByPurposeWithoutShrinkingTheTarget() {
        String message="选中的这两条生成清单";var state=new DialogueState();state.setPreviewId("count-recovery");
        var prepare=dispatch(state,message,Action.PREPARE_DISPATCH);
        var clarify=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"当前只有一条已选，请先完成两条选择");
        var requirements=Arrays.stream(SemanticReview.Aspect.values()).map(a->new SemanticReview.Requirement(a,message,"保留本轮明确的最终两条")).toList();
        var model=new Model(JsonUtil.toJson(clarify));model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.PREPARE_DISPATCH,TaskPurpose.QueryContext.NOT_QUERY,message)));
        model.reviews.add(JsonUtil.toJson(new SemanticReview(requirements,prepare,new SemanticReview.TargetCount(2,"这两条"))));
        model.reviews.add(JsonUtil.toJson(new SemanticReview(requirements,clarify,new SemanticReview.TargetCount(2,"这两条"))));
        var context=new AssistantPlanningContext(List.of(),Map.of(),p->Map.of("selectionAfter",Map.of("selectedCount",1)));
        assertEquals(clarify,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A"),context));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        assertTrue(model.reviewPrompts.get(1).getUserMessage().getText().contains("当前完整预检为 1 条"));
    }
    /** 真实链路出现过目标格式修正后仅剩一次完整复核；分阶段预算确保后续证据错误仍可有界恢复。 */
    @Test void purposeRepairDoesNotConsumeTheThreeCompleteReviewAttempts() throws Exception {
        String message="介绍能力";var purpose=new TaskPurpose(TaskPurpose.Purpose.HELP,TaskPurpose.QueryContext.NOT_QUERY,message);
        var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtil.MAPPER.valueToTree(purpose);invalid.putArray("evidence").add(message);
        var model=new Model(HELP);model.purposes.add(invalid.toString());model.purposes.add(JsonUtil.toJson(purpose));
        model.reviews.add("{}");model.expect(AssistantCodec.plan(HELP),"历史中才有的原话","无效来源");model.expect(AssistantCodec.plan(HELP),message,"说明当前能力");
        assertEquals(AssistantPlan.Route.HELP,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A")).route());
        assertEquals(2,model.purposePrompts.size());assertEquals(3,model.reviewPrompts.size());assertEquals(1,model.planningPrompts.size());
        assertFalse(model.reviewPrompts.get(2).getUserMessage().getText().contains("rejectedDraft"));
        var missing=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtil.MAPPER.valueToTree(purpose);missing.remove("requestedResult");
        assertThrows(ApiException.class,()->AssistantCodec.purpose(missing.toString()));
    }
    /** 目标结构失败仅在本阶段修正；原生Schema之外仍严格拒绝数组证据，不能吞错后假装目标已确定。 */
    @Test void purposeStructureRepairPrecedesDetailedReviewAndReservesItsBudget() throws Exception {
        String message="仅保留金额小于二十元的记录";var state=new DialogueState();
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("BUSINESS_QUERY");state.setAssistantFocus("BUSINESS_QUERY");
        var read=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","LT",List.of("20")))))),true);
        var purpose=new TaskPurpose(TaskPurpose.Purpose.BUSINESS_QUERY,TaskPurpose.QueryContext.FOLLOW_UP,message);
        var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtil.MAPPER.valueToTree(purpose);invalid.putArray("evidence").add(message);
        var model=new Model(JsonUtil.toJson(read));model.purposes.add(invalid.toString());model.purposes.add(JsonUtil.toJson(purpose));
        assertEquals(read,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        assertEquals(2,model.purposePrompts.size());assertEquals(1,model.reviewPrompts.size());
        assertTrue(model.purposePrompts.get(1).getUserMessage().getText().contains("purposeValidationError"));
        assertTrue(model.purposePrompts.get(0).getSystemMessage().getText().contains(AssistantSchema.purposeSchema()));
        assertFalse(model.purposePrompts.get(1).getUserMessage().getText().contains("expectedPlan"));
    }
    /** 不可解析的详细期望不能拖延目标判断；正确目标先固定，复核仍可在原预算内修好自己的结构。 */
    @Test void malformedFirstReviewAlreadyHasAnIndependentTaskPurpose() throws Exception {
        String message="不要配件，其他留下";var state=new DialogueState();
        state.setBusinessQuery(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of()));state.setAssistantFocus("BUSINESS_QUERY");state.setAssistantFocus("BUSINESS_QUERY");
        var read=read(query(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("productName","NE",List.of("配件")))))),true);
        var model=new Model(JsonUtil.toJson(read));model.reviews.add("{\"expectedPlan\":{\"route\":\"DISPATCH\"}}");model.expect(read,message,"普通查询筛选");
        assertEquals(read,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,state,List.of(),Set.of("A")));
        var first=JsonUtil.MAPPER.readTree(model.reviewPrompts.get(0).getUserMessage().getText());
        assertEquals("BUSINESS_QUERY",first.at("/taskPurpose/purpose").asText());assertEquals("FOLLOW_UP",first.at("/taskPurpose/queryContext").asText());
        assertEquals(1,model.purposePrompts.size());assertEquals(2,model.reviewPrompts.size());
    }
    @Test void persistedCurrentMessageHasOneEvidencePositionWithoutRenumberingOlderDuplicates() throws Exception {
        String message="继续查看";var model=new Model(HELP);
        var history=List.of(Map.of("role","user","content",message),Map.of("role","assistant","content","此前的答复"),Map.of("role","user","content",message));
        var context=new AssistantPlanningContext(history,Map.of(),p->Map.of());
        new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A"),context);
        for(var prompt:model.prompts) {
            var input=JsonUtil.MAPPER.readTree(prompt.getUserMessage().getText());
            if(!"TASK_PURPOSE".equals(input.path("taskStage").asText()))assertEquals(-1,input.path("messageIndex").asInt());
            assertEquals(2,input.path("recentConversation").size());
            assertEquals(0,input.at("/recentConversation/0/messageIndex").asInt());assertEquals(message,input.at("/recentConversation/0/content").asText());
            assertEquals(1,input.at("/recentConversation/1/messageIndex").asInt());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void actualHttpRequestsUseThreeNativeSchemasAndConfiguredThinkingWithoutTools(boolean thinking) throws Exception {
        var requests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            var req=JsonUtil.MAPPER.readTree(exchange.getRequestBody());requests.add(req);
            var schema=req.at("/response_format/json_schema/schema/properties");
            String content=schema.has("purpose")?JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.HELP,TaskPurpose.QueryContext.NOT_QUERY,"你能做什么"))
                    :schema.has("expectedPlan")?review(AssistantCodec.plan(HELP),"你能做什么","询问能力"):HELP;
            byte[] body=JsonUtil.toJson(Map.of("id","local","object","chat.completion","created",1,"model","test","choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",content))))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var api=org.springframework.ai.openai.api.OpenAiApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).apiKey("local-test").build();
            var model=org.springframework.ai.openai.OpenAiChatModel.builder().openAiApi(api).defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build()).build();
            var props=new AgentProperties();props.getSemantic().setThinkingEnabled(thinking);
            assertEquals(AssistantPlan.Route.HELP,new ModelAssistantPlanner(model,props).plan("你能做什么",new DialogueState(),List.of(),Set.of("A")).route());
            assertEquals(3,requests.size());
            for(var req:requests) {
                assertEquals("json_schema",req.at("/response_format/type").asText());assertTrue(req.at("/response_format/json_schema/strict").asBoolean());
                assertEquals(thinking?"enabled":"disabled",req.at("/thinking/type").asText());
                assertEquals(thinking?8192:4096,req.path("max_tokens").asInt());
                assertTrue(req.path("tools").isMissingNode() || req.path("tools").isEmpty());
            }
            assertFalse(requests.get(0).at("/response_format/json_schema/schema/properties/purpose").isMissingNode());
            assertFalse(requests.get(1).at("/response_format/json_schema/schema/properties/dispatch").isMissingNode());
            assertFalse(requests.get(2).at("/response_format/json_schema/schema/properties/expectedPlan").isMissingNode());
        } finally {server.stop(0);}
    }
}
