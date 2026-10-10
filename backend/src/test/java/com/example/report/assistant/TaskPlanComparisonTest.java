package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.semantic.SemanticIntent;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.report.semantic.SemanticIntent.*;

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
    /** 上下界分别核对；当前来源没有阈值记录时仍拒绝放宽等号，避免由结果巧合掩盖语义差异。 */
    @Test void inclusiveAndExclusiveBoundsDifferEvenWhenCurrentRowsWouldBeIdentical() {
        for(var operators:List.of(List.of("GTE","GT"),List.of("LTE","LT"))) {
            var inclusive=query(List.of(new BusinessQuery.Group(List.of(filter("amount",operators.get(0),"3200")))));
            var exclusive=query(List.of(new BusinessQuery.Group(List.of(filter("amount",operators.get(1),"3200")))));
            var columns=List.of(new com.example.report.catalog.query.FieldInfo("amount","decimal","金额，人民币元"));
            var rows=List.<Map<String,Object>>of(Map.of("rowKey","below","amount","680","currency","CNY"),
                    Map.of("rowKey","above","amount","8600","currency","CNY"));
            assertEquals(BusinessQueryEngine.execute(inclusive.query(),columns,rows,"合成边界事实").rows(),
                    BusinessQueryEngine.execute(exclusive.query(),columns,rows,"合成边界事实").rows());
            assertFalse(TaskPlanComparison.compare(MESSAGE,exclusive,review(inclusive,"阈值等号不得丢失",SemanticReview.Aspect.CONDITIONS)).isEmpty());
            var withBoundary=new ArrayList<>(rows);withBoundary.add(Map.of("rowKey","boundary","amount","3200.00","currency","CNY"));
            var included=BusinessQueryEngine.execute(inclusive.query(),columns,withBoundary,"合成边界事实");
            var excluded=BusinessQueryEngine.execute(exclusive.query(),columns,withBoundary,"合成边界事实");
            assertEquals(excluded.total()+1,included.total());
            assertTrue(included.rows().stream().anyMatch(row->"boundary".equals(row.get("rowKey"))));
            assertFalse(excluded.rows().stream().anyMatch(row->"boundary".equals(row.get("rowKey"))));
        }
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
    @Test void historyCanExplainMeaningButEveryRequirementQuotesCurrentRequest() {
        var actual=query(List.of());
        var evidence=List.of(new SemanticReview.Requirement(SemanticReview.Aspect.CONTINUITY,-1,MESSAGE,"本轮继续当前任务"),
                new SemanticReview.Requirement(SemanticReview.Aspect.ACTION,-1,MESSAGE,"结合历史理解本轮查询意图"));
        assertTrue(TaskPlanComparison.compare(MESSAGE,actual,new SemanticReview(evidence,actual)).isEmpty());
        assertThrows(ApiException.class,()->new SemanticReview.Requirement(SemanticReview.Aspect.ACTION,0,"查询销售记录","历史不能成为本轮授权"));
        var stale=new SemanticReview(List.of(new SemanticReview.Requirement(SemanticReview.Aspect.ACTION,-1,"先查询销售记录","即使改为当前下标，历史原话也不可用")),actual);
        assertThrows(ApiException.class,()->TaskPlanComparison.compare(MESSAGE,actual,stale));
    }
    @Test void clarificationWordingIsNotAnExecutableBehaviorChange() {
        var before=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"需要指定一条记录");
        var after=new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请补充所指记录的单据号");
        assertTrue(TaskPlanComparison.compare(MESSAGE,before,review(after,"完善相同澄清的表达",SemanticReview.Aspect.CAPABILITY)).isEmpty());
    }
    /** 范围修正不能要求伪造记录条件依据；同时改范围与选择则两类当前依据都不可缺少。 */
    @Test void dispatchScopeAndRecordChangesRequireTheirOwnCurrentEvidence() {
        var report=new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("销售"),MESSAGE);
        var company=new ScopeChange(Target.COMPANY,Operation.REPLACE,List.of("A"),MESSAGE);
        var record=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("设备甲"),MESSAGE);
        java.util.function.Function<List<ScopeChange>,AssistantPlan> plan=changes->AssistantPlan.dispatch(new DispatchDirective(
                new SemanticIntent(1,Action.PREVIEW,changes,List.of(),List.of(),List.of(),Clarify.NONE),
                DispatchDirective.Source.PREVIEW,"preview-p",List.of(),MESSAGE));
        var before=plan.apply(List.of(report));var scoped=plan.apply(List.of(company,report));var mixed=plan.apply(List.of(company,report,record));
        var scopeDifference=TaskPlanComparison.compare(MESSAGE,before,review(scoped,"公司与报表范围",SemanticReview.Aspect.SCOPE));
        assertEquals(List.of(SemanticReview.Aspect.SCOPE),scopeDifference.stream().map(TaskPlanComparison.Difference::aspect).toList());
        assertThrows(ApiException.class,()->TaskPlanComparison.compare(MESSAGE,before,review(mixed,"同时修改范围与选择",SemanticReview.Aspect.SCOPE)));
        var all=TaskPlanComparison.compare(MESSAGE,before,review(mixed,"两类修改均须当前依据",SemanticReview.Aspect.SCOPE,SemanticReview.Aspect.CONDITIONS));
        assertEquals(Set.of(SemanticReview.Aspect.SCOPE,SemanticReview.Aspect.CONDITIONS),all.stream().map(TaskPlanComparison.Difference::aspect).collect(java.util.stream.Collectors.toSet()));
        assertTrue(all.stream().allMatch(d->d.path().equals("/dispatch/intent/scopeChanges")));
    }
}
