package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.semantic.DialogueState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 单记录资格询问的路由及指代回归；用模型草稿替身验证安全边界，不宣称真实模型已经理解自然语言。 */
class EligibilityPlanningTest {
    private static final String REPORT="rpt-sales-order";

    private DialogueState queried() {
        var state=new DialogueState();state.setAssistantFocus("BUSINESS_QUERY");
        state.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(REPORT),"A",
                List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("productName","EQ",List.of("服务器")),
                        new BusinessQuery.Filter("status","EQ",List.of("未派单"))))),null,false,1,20,null));
        state.setBusinessReferences(List.of(reference("1","SO2026001","服务器")));state.setBusinessTotalCount(1L);
        return state;
    }
    private Map<String,String> reference(String id,String docNo,String product) {
        return Map.of("reportId",REPORT,"recordId",id,"docNo",docNo,"companyCode","A","productName",product);
    }
    private AssistantPlan eligibility(String id,boolean followUp) {
        return new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.ELIGIBILITY,
                List.of(REPORT),"A",List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of(id))))),null,false,1,20,null),followUp,null);
    }

    @Test void qualificationQuestionRepairsDispatchRouteAndKeepsTheDisplayedIdentity() {
        var state=queried();String message="这条数据符合派单条件吗";
        var wrong=AssistantPlan.dispatch(new DispatchDirective(ModelAssistantPlannerTest.intent(com.example.report.semantic.SemanticIntent.Action.PREPARE_DISPATCH),
                DispatchDirective.Source.QUERY_ROWS,AssistantReferences.queryRef(state),List.of("row-1"),message));
        var expected=eligibility("1",true);
        var model=ModelAssistantPlannerTest.repair(wrong,expected,message,"本轮仅要求只读资格核验",state);
        assertEquals("BUSINESS_QUERY",state.getAssistantFocus());assertNull(state.getPreviewId());
        assertEquals(1,model.reviewPrompts.size());
        assertDoesNotThrow(()->AssistantRouteGuard.validateRefinement(message,state,expected));
    }
    @Test void unshownIdentityIsRejectedAndAmbiguousPronounNeedsSemanticClarification() {
        var state=queried();
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("这条能派单吗",state,eligibility("99",true)));
        state.setBusinessReferences(List.of(reference("1","SO2026001","服务器"),reference("2","SO2026002","交换机")));
        var clarify=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"当前有两条，请明确所指单据或产品");
        ModelAssistantPlannerTest.repair(eligibility("1",true),clarify,"这条能派单吗","多对象指代没有唯一依据",state);
        assertDoesNotThrow(()->AssistantRouteGuard.validate("服务器那条能派单吗",state,eligibility("1",true)));
    }
    @Test void failedQueryCannotProvideIdentityButExplicitIndependentIdCan() {
        var state=queried();state.setBusinessUnresolved(true);
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("这条能派单吗",state,eligibility("1",true)));
        assertDoesNotThrow(()->AssistantRouteGuard.validate("核验销售记录编号1能否派单",state,eligibility("1",false)));
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("这条能派单吗",state,eligibility("99",false)));
    }
    @Test void qualificationProtocolCannotBroadenScopeOrPrefilterTheOutcome() {
        var identity=List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of("1")))));
        assertThrows(ApiException.class,()->new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.ELIGIBILITY,List.of(),"A",identity,null,false,1,20,null));
        assertThrows(ApiException.class,()->new BusinessQuery(BusinessQuery.Domain.DISPATCH,BusinessQuery.View.ELIGIBILITY,List.of(REPORT),"A",identity,null,false,1,20,null));
        assertThrows(ApiException.class,()->new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.ELIGIBILITY,List.of(REPORT),"A",identity,null,false,2,20,null));
        var status=List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of("1")),new BusinessQuery.Filter("status","EQ",List.of("未派单")))));
        assertThrows(ApiException.class,()->new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.ELIGIBILITY,List.of(REPORT),"A",status,null,false,1,20,null));
    }
    @Test void incompleteEvidenceCannotDefaultToFalse() {
        assertThrows(RuntimeException.class,()->JsonUtil.MAPPER.convertValue(Map.of("reason","未能核验","checkedFields",List.of()),DispatchEligibility.class));
        assertThrows(ApiException.class,()->new DispatchEligibility(true,"符合",null,null,null,null,List.of()));
    }
    @Test void independentConditionExpectationReceivesTheSameReadOnlyValidation() throws Exception {
        for(var example:List.of(Map.of("report","rpt-sales-order","field","productName","description","产品名称","value","服务器"),
                Map.of("report","contracts","field","projectTitle","description","项目名称","value","城南专项"))) {
            var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(example.get("report")),"A",List.of(),null,false,1,20,null);
            var fields=BusinessFields.forDomain(BusinessQuery.Domain.REPORT,List.of(new com.example.report.catalog.query.FieldInfo(example.get("field"),"string",example.get("description"))));
            var row=Map.<String,Object>of("rowKey","r:1","reportId",example.get("report"),"recordId","1",example.get("field"),example.get("value"));
            var filtered=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,query.reportIds(),"A",
                    List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter(example.get("field"),"EQ",List.of(example.get("value")))))),null,false,1,20,null);
            String message="查一下"+example.get("value")+"的数据";
            var model=new ModelAssistantPlannerTest.Model(JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,query,false,null)),
                    JsonUtil.toJson(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,filtered,false,null)))
                    .expect(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,filtered,false,null),message,"遗漏业务对象限定");
            var context=new AssistantPlanningContext(List.of(),Map.of(),draft->BusinessAssistantService.queryEvidence(BusinessQueryEngine.execute(draft.query(),fields,List.of(row),"内存来源事实")));
            var result=new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A"),context);
            assertEquals(filtered,result.query());assertEquals(1,model.reviewPrompts.size());
            assertFalse(JsonUtil.MAPPER.readTree(model.reviewPrompts.get(0).getUserMessage().getText()).has("readEvidence"));
            var evidence=JsonUtil.MAPPER.readTree(model.planningPrompts.get(1).getUserMessage().getText()).at("/attemptHistory/0/readEvidence");
            assertTrue(evidence.path("columns").toString().contains(example.get("field")));assertTrue(evidence.path("rows").toString().contains(example.get("value")));
        }
    }
    @Test void evidenceBudgetKeepsTotalAndMarksTruncation() {
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of(REPORT),"A",List.of(),null,false,1,50,null);
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<30;i++)rows.add(Map.of("rowKey","r:"+i,"productName","产品"+i));
        var result=BusinessQueryEngine.execute(query,BusinessFields.forDomain(BusinessQuery.Domain.REPORT,List.of(new com.example.report.catalog.query.FieldInfo("productName","string","产品"))),rows,"内存来源事实");
        var evidence=BusinessAssistantService.queryEvidence(result);
        assertEquals(30,evidence.get("totalCount"));assertEquals(20,((List<?>)evidence.get("rows")).size());
        assertEquals(false,evidence.get("evidenceCoversDisplayedRows"));assertEquals(false,evidence.get("allMatchesIncluded"));assertEquals(30,result.rows().size());
    }
}
