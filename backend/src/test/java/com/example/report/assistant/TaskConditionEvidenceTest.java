package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.semantic.DialogueState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 字段来源和当前有效状态的程序回归；撤销、错位证据及不存在的对象不能污染完整期望。 */
class TaskConditionEvidenceTest {
    private static final String MESSAGE="继续查询这批记录";
    private final AssistantPlanningContext context=AssistantPlanningContext.empty(p->{});
    private AssistantPlan query(String field,String operator,String value,boolean followUp) {
        return new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("sales"),"A",
                List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter(field,operator,List.of(value))))),null,false,1,20,null),followUp,null);
    }
    private SemanticReview review(AssistantPlan plan,List<SemanticReview.ConditionCheck> checks) {
        return new SemanticReview(List.of(new SemanticReview.Requirement(SemanticReview.Aspect.CONDITIONS,MESSAGE,"核对本轮条件来源")),checks,plan,null);
    }
    private SemanticReview.ConditionCheck check(AssistantPlan plan,SemanticReview.ConditionOrigin origin){return new SemanticReview.ConditionCheck(plan.query().conditions().get(0).allOf().get(0),origin,MESSAGE,"");}
    @Test void inheritedConditionsMustStillExistInTheSuccessfulQuery() {
        var plan=query("amount","GTE","3200",true);var proof=review(plan,List.of(check(plan,SemanticReview.ConditionOrigin.ACTIVE_QUERY)));
        var state=new DialogueState();state.setBusinessQuery(plan.query());
        assertDoesNotThrow(()->TaskConditionEvidence.validate(MESSAGE,state,context,proof));
        state.setBusinessQuery(query("amount","GT","3200",true).query());
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,proof));
        state.setBusinessQuery(query("expenseType","NE","办公用品",true).query());
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,proof));
        state.setBusinessQuery(plan.query());state.setBusinessUnresolved(true);
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,proof));
    }
    @Test void conditionsRequireCompleteUniqueBindingsAndCurrentEvidence() throws Exception {
        var plan=query("productName","EQ","服务器",false);var state=new DialogueState();
        var proof=check(plan,SemanticReview.ConditionOrigin.CURRENT_REQUEST);
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,review(plan,List.of())));
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,review(plan,List.of(proof,proof))));
        var nonexistent=new SemanticReview.ConditionCheck(new BusinessQuery.Filter("amount","GT",List.of("10")),proof.origin(),MESSAGE,"");
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,review(plan,List.of(nonexistent))));
        var old=new SemanticReview.ConditionCheck(proof.condition(),proof.origin(),"只在历史里出现","");
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,review(plan,List.of(old))));
        var wire=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtil.MAPPER.valueToTree(review(plan,List.of(proof)));wire.remove("conditionChecks");
        assertThrows(ApiException.class,()->AssistantCodec.review(wire.toString()));
    }
    @Test void objectConditionsMustMatchTheDisplayedFieldAndCannotInventOtherRows() {
        var state=new DialogueState();state.setBusinessReferences(List.of(Map.of("recordId","1","productName","服务器")));
        var proof=List.of(check(query("recordId","EQ","1",true),SemanticReview.ConditionOrigin.VISIBLE_OBJECT));
        assertDoesNotThrow(()->TaskConditionEvidence.validate(MESSAGE,state,context,review(query("recordId","EQ","1",true),proof)));
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,review(query("recordId","EQ","2",true),proof)));
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,review(query("productName","EQ","1",true),proof)));
    }
    @Test void evidenceOrderCannotSwapInheritedLowerBoundAndNewUpperBound() {
        var state=new DialogueState();state.setBusinessQuery(query("amount","GTE","32000",true).query());
        String message="改一下，上限九万六，也含九万六。";
        var lower=new BusinessQuery.Filter("amount","GTE",List.of("32000"));var upper=new BusinessQuery.Filter("amount","LTE",List.of("96000"));
        for(var order:List.of(List.of(lower,upper),List.of(upper,lower))) {
            var base=query("amount","GTE","32000",true).query();
            var plan=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,new BusinessQuery(base.domain(),base.view(),base.reportIds(),base.companyCode(),
                    List.of(new BusinessQuery.Group(order)),null,false,1,20,null),true,null);
            var checks=List.of(new SemanticReview.ConditionCheck(upper,SemanticReview.ConditionOrigin.CURRENT_REQUEST,message,""),
                    new SemanticReview.ConditionCheck(lower,SemanticReview.ConditionOrigin.ACTIVE_QUERY,"改一下",""));
            assertDoesNotThrow(()->TaskConditionEvidence.validate(message,state,context,review(plan,checks)));
        }
    }
    /** 金额字段仍在不能掩盖旧下限丢失；修改上限的原话也不能被改标为下限或整字段授权。 */
    @Test void upperBoundChangeCannotEraseTheExistingLowerBound() {
        var state=new DialogueState();state.setBusinessQuery(query("amount","GTE","32000",true).query());
        String message="改一下，上限九万六，也含九万六。";
        var plan=query("amount","LTE","96000",true);
        var checks=List.of(new SemanticReview.ConditionCheck(plan.query().conditions().get(0).allOf().get(0),SemanticReview.ConditionOrigin.CURRENT_REQUEST,message,""));
        var missing=review(plan,checks);
        assertTrue(assertThrows(ApiException.class,()->TaskConditionEvidence.validate(message,state,context,missing)).getMessage().contains("priorConditionChanges遗漏"));
        var change=new SemanticReview.PriorConditionChange(state.getBusinessQuery().conditions().get(0).allOf().get(0),message);
        var proof=new SemanticReview(missing.requirements(),checks,List.of(change),plan,null);
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(message,state,context,proof));
    }
    /** 删除端点与重设整个字段须分别给出当前授权；其余端点的有效来源保持不变。 */
    @Test void explicitEndpointRemovalAndFieldResetHaveSeparateCurrentEvidence() {
        var lower=new BusinessQuery.Filter("amount","GTE",List.of("680"));var upper=new BusinessQuery.Filter("amount","LTE",List.of("3200"));
        var state=new DialogueState();var base=query("amount","GTE","680",true).query();
        state.setBusinessQuery(new BusinessQuery(base.domain(),base.view(),base.reportIds(),base.companyCode(),List.of(new BusinessQuery.Group(List.of(lower,upper))),null,false,1,20,null));
        String message="下限不再限制，上限不变。";var plan=query("amount","LTE","3200",true);
        var checks=List.of(new SemanticReview.ConditionCheck(upper,SemanticReview.ConditionOrigin.ACTIVE_QUERY,"上限不变",""));
        var proof=new SemanticReview(review(plan,checks).requirements(),checks,List.of(new SemanticReview.PriorConditionChange(lower,"下限不再限制")),plan,null);
        assertDoesNotThrow(()->TaskConditionEvidence.validate(message,state,context,proof));
        var stale=new SemanticReview(proof.requirements(),checks,List.of(new SemanticReview.PriorConditionChange(lower,"撤销昨天的下限")),plan,null);
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(message,state,context,stale));
        String reset="重新设置整个金额条件，只要求不超过50元。";var replacement=query("amount","LTE","50",true);
        var resetChecks=List.of(new SemanticReview.ConditionCheck(replacement.query().conditions().get(0).allOf().get(0),SemanticReview.ConditionOrigin.CURRENT_REQUEST,"不超过50元",""));
        var changes=List.of(lower,upper).stream().map(old->new SemanticReview.PriorConditionChange(old,"重新设置整个金额条件")).toList();
        assertDoesNotThrow(()->TaskConditionEvidence.validate(reset,state,context,new SemanticReview(proof.requirements(),resetChecks,changes,replacement,null)));
    }
    @Test void lowerBoundChangeCannotEraseTheExistingUpperBoundAndEquivalentDecimalsAreRetained() {
        var state=new DialogueState();state.setBusinessQuery(query("amount","LTE","500",true).query());
        String message="lower bound becomes 20";var plan=query("amount","GTE","20",true);
        var checks=List.of(new SemanticReview.ConditionCheck(plan.query().conditions().get(0).allOf().get(0),SemanticReview.ConditionOrigin.CURRENT_REQUEST,message,""));
        var change=new SemanticReview.PriorConditionChange(state.getBusinessQuery().conditions().get(0).allOf().get(0),message);
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(message,state,context,new SemanticReview(review(plan,checks).requirements(),checks,List.of(change),plan,null)));
        state.setBusinessQuery(query("amount","GTE","12.50",true).query());var equivalent=query("amount","GTE","12.5",true);
        assertDoesNotThrow(()->TaskConditionEvidence.validate(MESSAGE,state,context,review(equivalent,List.of(check(equivalent,SemanticReview.ConditionOrigin.ACTIVE_QUERY)))));
    }
    @Test void independentQueriesCannotClaimOldConditionChangesAndTheWireRequiresTheNewField() throws Exception {
        var state=new DialogueState();state.setBusinessQuery(query("amount","GT","20",true).query());
        var plan=query("productName","EQ","设备",false);var checks=List.of(check(plan,SemanticReview.ConditionOrigin.CURRENT_REQUEST));
        var change=new SemanticReview.PriorConditionChange(state.getBusinessQuery().conditions().get(0).allOf().get(0),MESSAGE);
        var proof=new SemanticReview(review(plan,checks).requirements(),checks,List.of(change),plan,null);
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,state,context,proof));
        var wire=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtil.MAPPER.valueToTree(review(plan,checks));wire.remove("priorConditionChanges");
        assertThrows(ApiException.class,()->AssistantCodec.review(wire.toString()));
    }
    /** 服务返回展开后的授权范围，模型继续使用空数组表示全部，两者仍须按同范围追问逐项核对旧条件。 */
    @Test void expandedAuthorizedScopeAndAllScopeHaveTheSameConditionHistory() {
        var oldId=new BusinessQuery.Filter("orderId","EQ",List.of("WO-OLD"));var newId=new BusinessQuery.Filter("orderId","EQ",List.of("WO-NEW"));
        var state=new DialogueState();state.setBusinessQuery(new BusinessQuery(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.DETAIL,List.of("sales","expense"),"A",
                List.of(new BusinessQuery.Group(List.of(oldId))),null,false,1,20,null));
        var next=new BusinessQuery(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.DETAIL,List.of(),null,List.of(new BusinessQuery.Group(List.of(newId))),null,false,1,20,null);
        var plan=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,next,true,null);String message="改看WO-NEW";
        var checks=List.of(new SemanticReview.ConditionCheck(newId,SemanticReview.ConditionOrigin.CURRENT_REQUEST,message,""));
        var changes=List.of(new SemanticReview.PriorConditionChange(oldId,message));
        var proof=new SemanticReview(review(plan,checks).requirements(),checks,changes,plan,null);
        assertDoesNotThrow(()->TaskConditionEvidence.validate(message,state,context,proof,Set.of("sales","expense"),Set.of("A")));
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(message,state,context,review(plan,checks),Set.of("sales","expense"),Set.of("A")));
        assertThrows(ApiException.class,()->TaskConditionEvidence.validate(message,state,context,proof,Set.of("sales","expense"),Set.of("A","B")));
        var status=new BusinessQuery(BusinessQuery.Domain.WORK_ORDER,BusinessQuery.View.LIST,List.of(),null,List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("status","EQ",List.of("已完成"))))),null,false,1,20,null);
        assertThrows(ApiException.class,()->AssistantRouteGuard.validate("仅改状态",state,new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,status,true,null),Set.of("sales","expense"),Set.of("A")));
    }
    @Test void repeatingTheCurrentBoundaryCannotInventAnOlderStrictComparison() {
        String message="下限仍为不少于3200元";var plan=query("amount","GTE","3200",true);var state=new DialogueState();state.setBusinessQuery(plan.query());
        var checks=List.of(new SemanticReview.ConditionCheck(plan.query().conditions().get(0).allOf().get(0),SemanticReview.ConditionOrigin.CURRENT_REQUEST,message,""));
        var nonexistent=new SemanticReview.PriorConditionChange(new BusinessQuery.Filter("amount","GT",List.of("3200")),message);
        var proof=new SemanticReview(review(plan,checks).requirements(),checks,List.of(nonexistent),plan,null);
        var failure=assertThrows(com.example.report.common.ModelContractViolation.class,()->TaskConditionEvidence.validate(message,state,context,proof));
        assertTrue(failure.feedback().contains("priorConditionChanges[0].condition"));assertTrue(failure.feedback().contains("当前可继承旧条件"));
        assertTrue(failure.feedback().contains("本轮被替换或删除的旧条件=[]"));
        assertDoesNotThrow(()->TaskConditionEvidence.validate(message,state,context,review(plan,checks)));
    }
    @Test void alreadyAppliedSelectionDoesNotBecomeANewConditionProof() {
        var plan=new AssistantPlan(AssistantPlan.Route.HELP,null,false,null);
        var historical=new SemanticReview.ConditionCheck(new BusinessQuery.Filter("expenseType","EQ",List.of("业务招待费")),
                SemanticReview.ConditionOrigin.CURRENT_REQUEST,MESSAGE,"");
        var failure=assertThrows(ApiException.class,()->TaskConditionEvidence.validate(MESSAGE,new DialogueState(),context,review(plan,List.of(historical))));
        assertTrue(failure.getMessage().contains("conditionChecks必须为[]"));
    }
}
