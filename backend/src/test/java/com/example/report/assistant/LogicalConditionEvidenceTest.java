package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.semantic.DialogueState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 原文连接关系的程序回归；检查独立证据、括号、值内连接词和盲式修正，不代替真实模型验收。 */
class LogicalConditionEvidenceTest {
    private static final BusinessQuery.Filter A=new BusinessQuery.Filter("date","IS_NULL",List.of());
    private static final BusinessQuery.Filter B=new BusinessQuery.Filter("amount","EQ",List.of("0.01"));
    private static final BusinessQuery.Filter C=new BusinessQuery.Filter("status","EQ",List.of("未派单"));
    private static final BusinessQuery.Filter D=new BusinessQuery.Filter("productName","EQ",List.of("设备"));
    private static final String EA="业务日期为空",EB="金额等于0.01元",EC="状态等于未派单",ED="产品名称等于设备";
    private static BusinessQuery.Group group(BusinessQuery.Filter... filters){return new BusinessQuery.Group(List.of(filters));}
    private static AssistantPlan plan(BusinessQuery.Group... groups){return new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,
            new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r"),"A",List.of(groups),null,false,1,20,null),false,null);}
    private static SemanticReview.ConditionCheck proof(BusinessQuery.Filter condition,String evidence){return new SemanticReview.ConditionCheck(condition,SemanticReview.ConditionOrigin.CURRENT_REQUEST,evidence,"");}
    private static SemanticReview review(String message,AssistantPlan plan,SemanticReview.ConditionCheck... checks){return new SemanticReview(
            Arrays.stream(SemanticReview.Aspect.values()).map(aspect->new SemanticReview.Requirement(aspect,message,"按原文核对动作、范围和条件组合")).toList(),List.of(checks),plan,null);}
    private static void validate(String message,SemanticReview review){TaskConditionEvidence.validate(message,new DialogueState(),AssistantPlanningContext.empty(p->{}),review);}

    /** 真实失败原文：两个原子条件本身正确，也不能将 OR 变为 AND。 */
    @Test void originalNullOrAmountFailureIsRejectedWithoutMutatingTheReview(){
        String message="列出A公司的销售报表，业务日期为空，或者金额等于0.01元。";
        var wrong=review(message,plan(group(A,B)),proof(A,EA),proof(B,EB));String before=JsonUtil.toJson(wrong);
        var failure=assertThrows(ApiException.class,()->validate(message,wrong));assertTrue(failure.getMessage().contains("明确逻辑连接"));
        assertEquals(before,JsonUtil.toJson(wrong));assertDoesNotThrow(()->validate(message,review(message,plan(group(A),group(B)),proof(B,EB),proof(A,EA))));
    }
    @ParameterizedTest @ValueSource(strings={"或者","或","OR","or","||","∨"})
    void explicitDisjunctionCannotBecomeConjunction(String connector){
        String message="查询记录："+EA+"，"+connector+" "+EB+"。";
        assertThrows(ApiException.class,()->validate(message,review(message,plan(group(A,B)),proof(A,EA),proof(B,EB))));
        assertDoesNotThrow(()->validate(message,review(message,plan(group(B),group(A)),proof(A,EA),proof(B,EB))));
    }
    @ParameterizedTest @ValueSource(strings={"并且","且","AND","and","&&","∧","同时"})
    void explicitConjunctionCannotBecomeDisjunction(String connector){
        String message="查询记录："+EA+"，"+connector+" "+EB+"。";
        assertThrows(ApiException.class,()->validate(message,review(message,plan(group(A),group(B)),proof(A,EA),proof(B,EB))));
        assertDoesNotThrow(()->validate(message,review(message,plan(group(B,A)),proof(A,EA),proof(B,EB))));
    }
    @Test void parenthesizedMixedLogicUsesDistributionAndIgnoresGroupOrder(){
        String message="查询记录：（"+EA+"或者"+EB+"）并且（"+EC+"或者"+ED+"）。";
        var checks=new SemanticReview.ConditionCheck[]{proof(A,EA),proof(B,EB),proof(C,EC),proof(D,ED)};
        var correct=plan(group(D,B),group(A,C),group(B,C),group(D,A));
        assertDoesNotThrow(()->validate(message,review(message,correct,checks)));
        assertThrows(ApiException.class,()->validate(message,review(message,plan(group(A,B),group(C,D)),checks)));
        assertThrows(ApiException.class,()->validate(message,review(message,plan(group(A,B,C,D)),checks)));
    }
    @Test void redundantParenthesesAndRepeatedDnfAtomsDoNotRequireRepeatedEvidence(){
        String message="查询记录：(("+EA+"或者"+EB+"))并且"+EC+"。";
        var checks=new SemanticReview.ConditionCheck[]{proof(A,EA),proof(B,EB),proof(C,EC)};
        assertDoesNotThrow(()->validate(message,review(message,plan(group(A,C),group(B,C)),checks)));
        assertThrows(ApiException.class,()->validate(message,review(message,plan(group(A),group(B,C)),checks)));
    }
    @Test void connectorInsideQuotedFieldValueIsPartOfTheAtom(){
        var name=new BusinessQuery.Filter("productName","EQ",List.of("甲或乙(and丙)"));String evidence="产品名称等于“甲或乙(and丙)”";
        String message="查询记录："+evidence+"并且"+EB+"。";
        assertDoesNotThrow(()->validate(message,review(message,plan(group(name,B)),proof(name,evidence),proof(B,EB))));
        assertThrows(ApiException.class,()->validate(message,review(message,plan(group(name),group(B)),proof(name,evidence),proof(B,EB))));
    }
    @Test void ambiguousMixedPrecedenceAndUnsupportedConnectorsStayWithModelReview(){
        for(String message:List.of("查询"+EA+"或者"+EB+"并且"+EC,"查询"+EA+"，"+EB+"以及"+EC,
                "不要满足（"+EA+"或者"+EB+"）且"+EC,"查询（"+EA+"或者"+EB+"且"+EC)){
            assertDoesNotThrow(()->validate(message,review(message,plan(group(A,B,C)),proof(A,EA),proof(B,EB),proof(C,EC))));
        }
    }
    @Test void overlappingRepeatedOrMissingUniqueAnchorsCannotInventALogicalRelation(){
        String message="查询"+EA+"或者"+EB;
        assertDoesNotThrow(()->validate(message,review(message,plan(group(A,B)),proof(A,message),proof(B,message))));
        assertDoesNotThrow(()->validate(message,review(message,plan(group(A,B)),proof(A,EA+"或者"+EB),proof(B,EB))));
        String repeated=message+"；再次说明"+EA;
        assertDoesNotThrow(()->validate(repeated,review(repeated,plan(group(A,B)),proof(A,EA),proof(B,EB))));
    }
    @Test void negatedAtomsAndInheritedConditionsAreNotTreatedAsIndependentCurrentPositiveEvidence(){
        String message="查询不要"+EA+"或者"+EB;
        var negated=new SemanticReview.ConditionCheck(A,SemanticReview.ConditionOrigin.CURRENT_REQUEST,"不要"+EA,"不要");
        assertDoesNotThrow(()->validate(message,review(message,plan(group(A,B)),negated,proof(B,EB))));
        String follow="保留"+EA+"，或者改用"+EB;var state=new DialogueState();state.setBusinessQuery(plan(group(A)).query());
        var after=new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,plan(group(A,B)).query(),true,null);
        var inherited=new SemanticReview.ConditionCheck(A,SemanticReview.ConditionOrigin.ACTIVE_QUERY,"保留"+EA,"");
        assertDoesNotThrow(()->TaskConditionEvidence.validate(follow,state,AssistantPlanningContext.empty(p->{}),review(follow,after,inherited,proof(B,EB))));
    }
    /** 错误复核应在自身预算内修正，正确草稿不能接收到错误 AND 作为冻结期望。 */
    @Test void wrongReviewRepairsBlindlyBeforeItCanCorruptTheCorrectDraft() throws Exception {
        String message="列出A公司的销售报表，"+EA+"，或者"+EB+"。";var correct=plan(group(A),group(B));
        var model=new ModelAssistantPlannerTest.Model(JsonUtil.toJson(correct));
        model.reviews.add(JsonUtil.toJson(review(message,plan(group(A,B)),proof(A,EA),proof(B,EB))));
        model.reviews.add(JsonUtil.toJson(review(message,correct,proof(A,EA),proof(B,EB))));
        assertEquals(correct,new ModelAssistantPlanner(model,new AgentProperties()).plan(message,new DialogueState(),List.of(),Set.of("A")));
        assertEquals(1,model.planningPrompts.size());assertEquals(2,model.reviewPrompts.size());
        var repair=JsonUtil.MAPPER.readTree(model.reviewPrompts.get(1).getUserMessage().getText());
        assertTrue(repair.path("reviewValidationError").asText().contains("明确逻辑连接"));
        assertFalse(repair.has("proposedPlan"));assertFalse(repair.has("rejectedDraft"));assertFalse(repair.has("differences"));
    }
}
