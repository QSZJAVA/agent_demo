package com.example.report.assistant;

import com.example.report.analysis.AnalysisContract;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.common.ModelContractViolation;
import com.example.report.config.AgentProperties;
import com.example.report.semantic.DialogueState;
import com.example.report.support.TestCatalog;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.report.analysis.AnalysisContract.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

/** 统一聊天的知识/分析路由、来源绑定及三阶段一致性回归；预设模型响应只验证守卫与修正机制，不证明真实语义能力。 */
class UnifiedAnalysisRoutingTest {
    private static final String SOURCE="analysis-source-"+"a".repeat(24),OTHER="analysis-source-"+"b".repeat(24);
    private static final String QUESTION="只分析刚才这些销售记录，按产品统计金额，不重新查询，也不要派单。";
    private static final Set<String> REPORTS=Set.of(TestCatalog.SALES,TestCatalog.EXPENSE),COMPANIES=Set.of("A","B");
    private static AssistantPlan plan(AnalysisContract.Mode mode,String source){return AssistantPlan.analysis(new AnalysisDirective("A",TestCatalog.SALES,mode,source));}
    private static BusinessQuery query(){return new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(TestCatalog.SALES),"A",List.of(),null,false,1,20,null);}
    private static DialogueState state(){var state=new DialogueState();state.setBusinessQuery(query());state.setAssistantFocus("BUSINESS_QUERY");state.setBusinessUnresolved(false);return state;}
    private static Map<String,Object> analysisContext(){return Map.of("available",true,"source",Map.of("sourceRef",SOURCE,"companyCode","A","reportId",TestCatalog.SALES,
            "query",query(),"observedAt","2026-10-13T01:00:00Z","expiresAt","2030-01-01T00:00:00Z","completeness","COMPLETE"));}
    private static SemanticReview review(AssistantPlan plan,SemanticReview.Aspect... aspects){return new SemanticReview(Arrays.stream(aspects).map(aspect->new SemanticReview.Requirement(aspect,QUESTION,"保留本轮读取约束和当前来源")).toList(),plan);}

    @Test void allAnalysisModesRoundTripOnlyThroughTheirMatchingRoute() {
        for(var plan:List.of(plan(KNOWLEDGE_ONLY,null),plan(CURRENT_DATA,null),plan(CURRENT_DATA,SOURCE),plan(SNAPSHOT_ONLY,SOURCE))) {
            assertEquals(plan,AssistantCodec.plan(JsonUtil.toJson(plan)));
            assertNull(plan.query());assertNull(plan.dispatch());assertFalse(plan.followUp());assertTrue(plan.removedFilters().isEmpty());
        }
        var knowledge=plan(KNOWLEDGE_ONLY,null);assertEquals(AssistantPlan.Route.KNOWLEDGE,knowledge.route());
        assertThrows(ApiException.class,()->new AssistantPlan(AssistantPlan.Route.ANALYSIS,null,false,null,List.of(),null,null,knowledge.analysis()));
        assertThrows(ApiException.class,()->new AssistantPlan(AssistantPlan.Route.KNOWLEDGE,null,false,null,List.of(),null,null,plan(CURRENT_DATA,null).analysis()));
        assertThrows(ApiException.class,()->new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query(),false,null,List.of(),null,null,knowledge.analysis()));
    }

    @Test void directiveRejectsArbitrarySnapshotIdsMissingModesAndDuplicatedQuestions() {
        assertThrows(ApiException.class,()->new AnalysisDirective("A",TestCatalog.SALES,null,null));
        assertThrows(ApiException.class,()->plan(KNOWLEDGE_ONLY,SOURCE));
        assertThrows(ApiException.class,()->plan(SNAPSHOT_ONLY,null));
        for(String reference:List.of("a".repeat(32),"query-source-"+"a".repeat(24),"analysis-source-"+"A".repeat(24),SOURCE+"\n"))
            assertThrows(ApiException.class,()->plan(SNAPSHOT_ONLY,reference));
        var json=(ObjectNode)JsonUtil.MAPPER.valueToTree(plan(CURRENT_DATA,null));
        ((ObjectNode)json.get("analysis")).put("question","另一份看似等价的改写");
        assertThrows(ApiException.class,()->AssistantCodec.plan(json.toString()));
        ((ObjectNode)json.get("analysis")).remove("question");((ObjectNode)json.get("analysis")).remove("mode");
        assertThrows(ApiException.class,()->AssistantCodec.plan(json.toString()));
    }

    @Test void analysisIsAnExplicitRequiredWireFieldForEveryRoute() throws Exception {
        var help=(ObjectNode)JsonUtil.MAPPER.readTree(ModelAssistantPlannerTest.HELP);help.remove("analysis");
        assertThrows(ApiException.class,()->AssistantCodec.plan(help.toString()));
        var schema=JsonUtil.MAPPER.readTree(AssistantSchema.planSchema());assertTrue(schema.get("required").toString().contains("analysis"));
        var fields=schema.at("/properties/analysis/properties");
        assertEquals(Set.of("companyCode","reportId","mode","sourceRef"),fieldNames(fields));assertTrue(fields.path("question").isMissingNode());
        assertEquals("^analysis-source-[a-f0-9]{24}$",fields.at("/sourceRef/pattern").asText());
    }

    @Test void independentPurposeRetainsKnowledgeAndAnalysisWithoutDispatchCounts() {
        for(var purpose:List.of(TaskPurpose.Purpose.KNOWLEDGE,TaskPurpose.Purpose.ANALYSIS)) {
            var target=new TaskPurpose(purpose,TaskPurpose.QueryContext.NOT_QUERY,QUESTION);
            assertEquals(target,AssistantCodec.purpose(JsonUtil.toJson(target)));
            assertTrue(target.permits(plan(purpose==TaskPurpose.Purpose.KNOWLEDGE?KNOWLEDGE_ONLY:SNAPSHOT_ONLY,purpose==TaskPurpose.Purpose.KNOWLEDGE?null:SOURCE)));
            assertThrows(ApiException.class,()->new TaskPurpose(purpose,TaskPurpose.QueryContext.FOLLOW_UP,QUESTION));
            assertThrows(ApiException.class,()->new TaskPurpose(purpose,TaskPurpose.QueryContext.NOT_QUERY,QUESTION,new SemanticReview.TargetCount(2,QUESTION)));
        }
    }

    @Test void authorizedKnowledgeAndCurrentDataDoNotRequireAnExistingSnapshot() {
        var absent=Map.<String,Object>of("available",false,"source",Map.of());
        for(var plan:List.of(plan(KNOWLEDGE_ONLY,null),plan(CURRENT_DATA,null)))
            assertDoesNotThrow(()->AssistantRouteGuard.validateAnalysis(new DialogueState(),plan.analysis(),REPORTS,COMPANIES,absent));
        assertEquals(403,assertThrows(ApiException.class,()->AssistantRouteGuard.validateAnalysis(new DialogueState(),plan(KNOWLEDGE_ONLY,null).analysis(),Set.of(TestCatalog.EXPENSE),COMPANIES,absent)).getCode());
    }

    @Test void selectedSourceRequiresTheCurrentSuccessfulCompleteQueryAndMatchingScope() {
        var selected=plan(SNAPSHOT_ONLY,SOURCE).analysis();
        assertDoesNotThrow(()->AssistantRouteGuard.validateAnalysis(state(),selected,REPORTS,COMPANIES,analysisContext()));
        assertDoesNotThrow(()->AssistantRouteGuard.validateAnalysis(state(),plan(CURRENT_DATA,SOURCE).analysis(),REPORTS,COMPANIES,analysisContext()));
        var hidden=state();hidden.setAssistantFocus("DISPATCH");assertThrows(ApiException.class,()->AssistantRouteGuard.validateAnalysis(hidden,selected,REPORTS,COMPANIES,analysisContext()));
        var failed=state();failed.setBusinessUnresolved(true);assertThrows(ApiException.class,()->AssistantRouteGuard.validateAnalysis(failed,selected,REPORTS,COMPANIES,analysisContext()));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateAnalysis(state(),selected,REPORTS,COMPANIES,Map.of("available",false,"source",Map.of())));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateAnalysis(state(),plan(SNAPSHOT_ONLY,OTHER).analysis(),REPORTS,COMPANIES,analysisContext()));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateAnalysis(state(),new AnalysisDirective("B",TestCatalog.SALES,SNAPSHOT_ONLY,SOURCE),REPORTS,COMPANIES,analysisContext()));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateAnalysis(state(),new AnalysisDirective("A",TestCatalog.EXPENSE,SNAPSHOT_ONLY,SOURCE),REPORTS,COMPANIES,analysisContext()));
        var source=new LinkedHashMap<>((Map<String,Object>)analysisContext().get("source"));source.put("completeness","PARTIAL");
        assertThrows(ApiException.class,()->AssistantRouteGuard.validateAnalysis(state(),selected,REPORTS,COMPANIES,Map.of("available",true,"source",source)));
    }

    @Test void knowledgeAndAnalysisRetainCompletedCompanyAndReportClarifications() {
        var company=new ClarificationOptions(ClarificationOptions.Kind.COMPANY,null,null,null,null,List.of("A","B"));
        var report=new ClarificationOptions(ClarificationOptions.Kind.REPORT,null,null,null,null,List.of(TestCatalog.SALES,TestCatalog.EXPENSE));
        var selectedCompany=new AssistantInteraction.SelectedClarification(new AssistantInteraction.PendingClarification("1".repeat(32),QUESTION,company,"permission","dictionary"),"A");
        var selectedReport=new AssistantInteraction.SelectedClarification(new AssistantInteraction.PendingClarification("2".repeat(32),QUESTION,report,"permission","dictionary",selectedCompany.choices()),TestCatalog.SALES);
        for(var plan:List.of(plan(KNOWLEDGE_ONLY,null),plan(CURRENT_DATA,null),plan(SNAPSHOT_ONLY,SOURCE))) {
            assertDoesNotThrow(()->AssistantInteraction.requireSelected(plan,selectedCompany,Map.of()));
            assertDoesNotThrow(()->AssistantInteraction.requireSelected(plan,selectedReport,Map.of()));
        }
    }

    @Test void analysisCannotDropAnEarlierScopeChoiceWhileKeepingTheLatestChoice() {
        var company=new ClarificationOptions(ClarificationOptions.Kind.COMPANY,null,null,null,null,List.of("A","B"));
        var report=new ClarificationOptions(ClarificationOptions.Kind.REPORT,null,null,null,null,List.of(TestCatalog.SALES,TestCatalog.EXPENSE));
        var selected=new AssistantInteraction.SelectedClarification(new AssistantInteraction.PendingClarification("2".repeat(32),QUESTION,report,"permission","dictionary",List.of(new AssistantInteraction.ResolvedChoice(company,"A"))),TestCatalog.SALES);
        for(var mode:List.of(KNOWLEDGE_ONLY,CURRENT_DATA,SNAPSHOT_ONLY)) {
            var wrongCompany=AssistantPlan.analysis(new AnalysisDirective("B",TestCatalog.SALES,mode,mode==SNAPSHOT_ONLY?SOURCE:null));
            var wrongReport=AssistantPlan.analysis(new AnalysisDirective("A",TestCatalog.EXPENSE,mode,mode==SNAPSHOT_ONLY?SOURCE:null));
            assertEquals(422,assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(wrongCompany,selected,Map.of())).getCode());
            assertEquals(422,assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(wrongReport,selected,Map.of())).getCode());
        }
    }

    @Test void analysisScopeDoesNotProveThatASelectedFieldOrValueWillBePreserved() {
        var field=new ClarificationOptions(ClarificationOptions.Kind.FIELD,BusinessQuery.Domain.REPORT,"A",TestCatalog.SALES,null,List.of("amount","product"));
        var value=new ClarificationOptions(ClarificationOptions.Kind.VALUE,BusinessQuery.Domain.REPORT,"A",TestCatalog.SALES,"product",List.of("手机","电脑"));
        for(var options:List.of(field,value)) {
            var selected=new AssistantInteraction.SelectedClarification(new AssistantInteraction.PendingClarification("3".repeat(32),QUESTION,options,"permission","dictionary"),options.values().get(0));
            for(var plan:List.of(plan(KNOWLEDGE_ONLY,null),plan(CURRENT_DATA,null),plan(SNAPSHOT_ONLY,SOURCE)))
                assertEquals(422,assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(plan,selected,Map.of("scope",Map.of("companyCode","A","reportIds",List.of(TestCatalog.SALES))))).getCode());
        }
    }

    @Test void completedAnalysisScopeAllowsAnotherDimensionButNotTheSameClarification() {
        var company=new ClarificationOptions(ClarificationOptions.Kind.COMPANY,null,null,null,null,List.of("A","B"));
        var report=new ClarificationOptions(ClarificationOptions.Kind.REPORT,null,null,null,null,List.of(TestCatalog.SALES,TestCatalog.EXPENSE));
        var selected=new AssistantInteraction.SelectedClarification(new AssistantInteraction.PendingClarification("4".repeat(32),QUESTION,company,"permission","dictionary"),"A");
        var repeatsCompany=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请选择公司",List.of(),null,company,null);
        var asksReport=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请选择报表",List.of(),null,report,null);
        assertThrows(ApiException.class,()->AssistantInteraction.requireSelected(repeatsCompany,selected,Map.of()));
        assertDoesNotThrow(()->AssistantInteraction.requireSelected(asksReport,selected,Map.of()));
    }

    @Test void modeScopeAndReferenceDifferencesNeedTheirOwnCurrentEvidence() {
        var selected=plan(SNAPSHOT_ONLY,SOURCE);var current=plan(CURRENT_DATA,null);
        var full=review(selected,SemanticReview.Aspect.ACTION,SemanticReview.Aspect.SCOPE,SemanticReview.Aspect.REFERENCE);
        var differences=TaskPlanComparison.compare(QUESTION,current,full);
        assertEquals(Map.of("/analysis/mode",SemanticReview.Aspect.ACTION,"/analysis/sourceRef",SemanticReview.Aspect.REFERENCE),differences.stream().collect(java.util.stream.Collectors.toMap(TaskPlanComparison.Difference::path,TaskPlanComparison.Difference::aspect)));
        assertThrows(ModelContractViolation.class,()->TaskPlanComparison.compare(QUESTION,current,review(selected,SemanticReview.Aspect.ACTION)));
        var other=AssistantPlan.analysis(new AnalysisDirective("B",TestCatalog.EXPENSE,SNAPSHOT_ONLY,SOURCE));
        var changed=TaskPlanComparison.compare(QUESTION,selected,review(other,SemanticReview.Aspect.SCOPE));
        assertEquals(Set.of("/analysis/companyCode","/analysis/reportId"),changed.stream().map(TaskPlanComparison.Difference::path).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void analysisRequirementCoverageCannotBeSatisfiedByUnrelatedConditions() {
        for(var plan:List.of(plan(KNOWLEDGE_ONLY,null),plan(CURRENT_DATA,null),plan(SNAPSHOT_ONLY,SOURCE)))
            assertThrows(ModelContractViolation.class,()->TaskPlanComparison.validateRequirementCoverage(review(plan,SemanticReview.Aspect.CONDITIONS)));
        assertDoesNotThrow(()->TaskPlanComparison.validateRequirementCoverage(review(plan(KNOWLEDGE_ONLY,null),SemanticReview.Aspect.ACTION,SemanticReview.Aspect.SCOPE)));
        assertThrows(ModelContractViolation.class,()->TaskPlanComparison.validateRequirementCoverage(review(plan(SNAPSHOT_ONLY,SOURCE),SemanticReview.Aspect.ACTION,SemanticReview.Aspect.SCOPE)));
        assertDoesNotThrow(()->TaskPlanComparison.validateRequirementCoverage(review(plan(SNAPSHOT_ONLY,SOURCE),SemanticReview.Aspect.ACTION,SemanticReview.Aspect.SCOPE,SemanticReview.Aspect.REFERENCE)));
    }

    @Test void allContextTransformationsPreserveTheSameServerAnalysisMetadata() {
        var context=new AssistantPlanningContext(List.of(Map.of("role","user","content",QUESTION)),Map.of(),ignored->Map.of()).withAnalysisContext(analysisContext());
        var changed=context.withCatalog(new TestCatalog().entries()).withRequestedQuery(query()).withResolvedClarification(Map.of("selectionCompleted",true)).beforeCurrentMessage(QUESTION);
        assertEquals(analysisContext(),changed.analysisContext());assertTrue(changed.history().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->changed.analysisContext().put("available",false));
    }

    @Test void allThreeModelStagesShareTheOriginalQuestionAndServerSnapshotMetadata() throws Exception {
        var selected=plan(SNAPSHOT_ONLY,SOURCE);var model=new ModelAssistantPlannerTest.Model(JsonUtil.toJson(selected)).expect(selected,QUESTION,"按当前完整快照分析，不重查来源");
        var calls=new AtomicInteger();var context=new AssistantPlanningContext(List.of(),Map.of(),draft->{calls.incrementAndGet();assertEquals(selected,draft);return Map.of("prechecked",true);}).withAnalysisContext(analysisContext());
        var result=new ModelAssistantPlanner(model,new AgentProperties()).plan(QUESTION,state(),new TestCatalog().entries(),COMPANIES,context);
        assertEquals(selected,result);assertEquals(1,calls.get());assertEquals(1,model.purposePrompts.size());assertEquals(1,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());
        for(var prompt:model.prompts) {
            var input=JsonUtil.MAPPER.readTree(prompt.getUserMessage().getText());assertEquals(QUESTION,input.get("message").asText());
            assertEquals(JsonUtil.MAPPER.valueToTree(analysisContext()),input.get("analysisContext"));
        }
    }

    @Test void independentReviewRepairsADataReadEscalationWithoutExecutingAnAnalysis() {
        var selected=plan(SNAPSHOT_ONLY,SOURCE);var current=plan(CURRENT_DATA,null);
        var model=new ModelAssistantPlannerTest.Model(JsonUtil.toJson(current),JsonUtil.toJson(selected)).expect(selected,QUESTION,"仅计算原快照，不能换成当前数据");
        var context=new AssistantPlanningContext(List.of(),Map.of(),ignored->Map.of("prechecked",true)).withAnalysisContext(analysisContext());
        assertEquals(selected,new ModelAssistantPlanner(model,new AgentProperties()).plan(QUESTION,state(),new TestCatalog().entries(),COMPANIES,context));
        assertEquals(2,model.planningPrompts.size());assertEquals(1,model.reviewPrompts.size());
        var repair=model.planningPrompts.get(1).getUserMessage().getText();assertTrue(repair.contains("/analysis/mode"));assertTrue(repair.contains("/analysis/sourceRef"));
    }

    @Test void anInvalidServerReferenceNeverReachesTheOuterPrecheck() {
        var wrong=plan(SNAPSHOT_ONLY,OTHER);var clarify=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请重新选择要分析的查询快照");
        var model=new ModelAssistantPlannerTest.Model(JsonUtil.toJson(wrong),JsonUtil.toJson(clarify)).expect(clarify,QUESTION,"当前引用不是服务器提供的来源");
        model.purposes.add(JsonUtil.toJson(new TaskPurpose(TaskPurpose.Purpose.ANALYSIS,TaskPurpose.QueryContext.NOT_QUERY,QUESTION)));
        var calls=new ArrayList<AssistantPlan>();var context=new AssistantPlanningContext(List.of(),Map.of(),draft->{calls.add(draft);return Map.of();}).withAnalysisContext(analysisContext());
        assertEquals(clarify,new ModelAssistantPlanner(model,new AgentProperties()).plan(QUESTION,state(),new TestCatalog().entries(),COMPANIES,context));
        assertEquals(List.of(clarify),calls);
    }

    private static Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node){var result=new HashSet<String>();node.fieldNames().forEachRemaining(result::add);return result;}
}
