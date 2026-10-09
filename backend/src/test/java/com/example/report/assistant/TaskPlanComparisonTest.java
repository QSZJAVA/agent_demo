package com.example.report.assistant;

import com.example.report.common.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 结构化语义比较的程序回归；覆盖字段值省略、否定、逻辑组合和来源变化，不把测试替身当真实模型证据。 */
class TaskPlanComparisonTest {
    private static final String MESSAGE="查询指定产品的销售记录，保留金额范围";
    private static BusinessQuery.Filter filter(String field,String operator,String value){return new BusinessQuery.Filter(field,operator,List.of(value));}
    private static AssistantPlan query(List<BusinessQuery.Group> conditions) {
        return new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,
                List.of("sales"),"A",conditions,null,false,1,20,null),false,null);
    }
    private static SemanticReview review(AssistantPlan expected,String meaning,SemanticReview.Aspect... aspects) {
        return new SemanticReview(Arrays.stream(aspects).map(a->new SemanticReview.Requirement(a,MESSAGE,meaning)).toList(),expected);
    }

    @Test void missingObjectConditionIsAComputedDifferenceAndFreeTextCannotReverseIt() {
        var required=query(List.of(new BusinessQuery.Group(List.of(filter("productName","EQ","设备甲")))));
        var assessment=review(required,"原文给出的产品必须限定到对应字段",SemanticReview.Aspect.CONDITIONS);
        var differences=TaskPlanComparison.compare(MESSAGE,query(List.of()),assessment);
        assertEquals(1,differences.size());assertEquals("/query/conditions",differences.get(0).path());
        assertTrue(differences.get(0).actual().isEmpty());assertTrue(differences.get(0).expected().toString().contains("设备甲"));
        // 比较采用结构化期望，说明文字无权另发一条与期望相反的删除条件命令。
        assertTrue(TaskPlanComparison.compare(MESSAGE,required,review(required,"已经符合要求，但删除条件",SemanticReview.Aspect.CONDITIONS)).isEmpty());
    }
    @Test void conjunctionOrderIsEquivalentButUnionNegationAndWeakerMatchingAreDifferent() {
        var product=filter("productName","EQ","设备甲");var minimum=filter("amount","GTE","10");var maximum=filter("amount","LTE","50");
        var expected=query(List.of(new BusinessQuery.Group(List.of(product,minimum,maximum))));
        var reordered=query(List.of(new BusinessQuery.Group(List.of(maximum,product,minimum))));
        assertTrue(TaskPlanComparison.compare(MESSAGE,reordered,review(expected,"同一交集",SemanticReview.Aspect.CONDITIONS)).isEmpty());
        for(var wrong:List.of(
                query(List.of(new BusinessQuery.Group(List.of(product,minimum)),new BusinessQuery.Group(List.of(maximum)))),
                query(List.of(new BusinessQuery.Group(List.of(filter("productName","NE","设备甲"),minimum,maximum)))),
                query(List.of(new BusinessQuery.Group(List.of(filter("productName","CONTAINS","设备甲"),minimum,maximum))))))
            assertFalse(TaskPlanComparison.compare(MESSAGE,wrong,review(expected,"不得丢弃逻辑",SemanticReview.Aspect.CONDITIONS)).isEmpty());
    }
    @Test void ungroundedChangesAndInventedEvidenceInvalidateReviewInsteadOfRejectingDraft() {
        var expected=query(List.of(new BusinessQuery.Group(List.of(filter("productName","EQ","设备甲")))));
        assertThrows(ApiException.class,()->TaskPlanComparison.compare(MESSAGE,query(List.of()),review(expected,"条件变化",SemanticReview.Aspect.ACTION)));
        var invented=new SemanticReview(List.of(new SemanticReview.Requirement(SemanticReview.Aspect.CONDITIONS,"用户未说过的内容","新增条件")),expected);
        assertThrows(ApiException.class,()->TaskPlanComparison.compare(MESSAGE,query(List.of()),invented));
    }
    @Test void reportScopeAndPaginationCannotHideBehindConditionEvidence() {
        var before=query(List.of());var q=before.query();
        var after=new AssistantPlan(before.route(),new BusinessQuery(q.domain(),q.view(),List.of(),null,q.conditions(),null,false,2,1,null),false,null);
        assertThrows(ApiException.class,()->TaskPlanComparison.compare(MESSAGE,before,review(after,"范围与分页变化",SemanticReview.Aspect.CONDITIONS)));
        var changes=TaskPlanComparison.compare(MESSAGE,before,review(after,"检查所有维度",SemanticReview.Aspect.SCOPE,SemanticReview.Aspect.PRESENTATION));
        assertEquals(Set.of("/query/reportIds","/query/companyCode","/query/page","/query/size"),changes.stream().map(TaskPlanComparison.Difference::path).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void historyCanExplainContinuationButCannotAuthorizeAChangedAction() {
        var history=List.of(Map.of("role","user","content","先查询销售记录"));
        var actual=query(List.of());
        var evidence=List.of(new SemanticReview.Requirement(SemanticReview.Aspect.CONTINUITY,-1,MESSAGE,"本轮继续当前任务"),
                new SemanticReview.Requirement(SemanticReview.Aspect.ACTION,0,"查询销售记录","历史确定查询意图"));
        assertTrue(TaskPlanComparison.compare(MESSAGE,history,actual,new SemanticReview(evidence,actual)).isEmpty());
        assertThrows(ApiException.class,()->TaskPlanComparison.compare(MESSAGE,history,new AssistantPlan(AssistantPlan.Route.HELP,null,false,null),new SemanticReview(evidence,actual)));
        assertThrows(ApiException.class,()->TaskPlanComparison.compare(MESSAGE,List.of(),actual,new SemanticReview(evidence,actual)));
        assertThrows(ApiException.class,()->TaskPlanComparison.compare(MESSAGE,history,actual,new SemanticReview(evidence.subList(1,2),actual)));
    }
    @Test void clarificationWordingIsNotAnExecutableBehaviorChange() {
        var before=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"需要指定一条记录");
        var after=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请补充所指记录的单据号");
        assertTrue(TaskPlanComparison.compare(MESSAGE,before,review(after,"完善相同澄清的表达",SemanticReview.Aspect.CAPABILITY)).isEmpty());
    }
}
