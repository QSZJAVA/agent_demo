package com.example.report.semantic;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.RecordKey;
import com.example.report.rule.*;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;
import static org.junit.jupiter.api.Assertions.*;

/** 客户选择的性质与边界回归：名称替换、稳定标识、别名歧义、集合数量、作用域及失败原子性；不测量模型准确率。 */
class CounterpartySelectionTest {
    final ReportCatalogService catalog=new ReportCatalogService(new TestCatalog().catalog(),new AgentProperties());
    Candidate row(String report,String id,String customerId,String name,List<String> aliases) {
        return SemanticEvaluation.withCustomer(candidate(report,id,"DOC-"+id,"A","与客户名称不同的摘要"),new CounterpartyRef(customerId,name,aliases));
    }
    ScopeChange change(Operation operation,String name,Quantifier quantity) {
        return new ScopeChange(Target.RECORDS,operation,List.of(name),"应收报表"+name,List.of("应收报表"),SelectorKind.COUNTERPARTY,quantity);
    }
    @Test void renamingEntityAndAddingOtherReportsPreservesSelectionAndInverse() {
        for(int i=0;i<80;i++) {
            String name="独立客户"+i+"有限公司",alias="企业"+i;
            var rows=List.of(row(RECEIVABLE,"a","stable",name,List.of(alias)),row(RECEIVABLE,"b","stable",name,List.of(alias)),
                    row(SALES,"s","stable",name,List.of(alias)),row(RECEIVABLE,"c","other","另一客户",List.of()));
            var previous=List.of(new RecordKey(SALES,"s"));
            var excluded=SelectionResolver.apply(rows,previous,change(Operation.EXCLUDE,alias,Quantifier.ALL),catalog,USER1);
            assertEquals(Set.of(new RecordKey(SALES,"s"),new RecordKey(RECEIVABLE,"a"),new RecordKey(RECEIVABLE,"b")),Set.copyOf(excluded));
            assertEquals(excluded,SelectionResolver.apply(rows,excluded,change(Operation.EXCLUDE,name,Quantifier.ALL),catalog,USER1));
            assertEquals(previous,SelectionResolver.apply(rows,excluded,change(Operation.RESTORE,name,Quantifier.ALL),catalog,USER1));
        }
    }
    @Test void allRecordsNeverMeansAllCustomersSharingAnAliasOrName() {
        var rows=List.of(row(RECEIVABLE,"a","first","同名客户",List.of("共享别名")),row(RECEIVABLE,"b","second","同名客户",List.of("共享别名")));
        for(String mention:List.of("共享别名","同名客户","同名"))
            assertThrows(ApiException.class,()->SelectionResolver.apply(rows,List.of(),change(Operation.EXCLUDE,mention,Quantifier.ALL),catalog,USER1));
    }
    @Test void oneAndUnspecifiedDoNotSilentlyExpandToMultipleInvoices() {
        var rows=List.of(row(RECEIVABLE,"a","one","客户一",List.of()),row(RECEIVABLE,"b","one","客户一",List.of()));
        for(var q:List.of(Quantifier.ONE,Quantifier.UNSPECIFIED))
            assertThrows(ApiException.class,()->SelectionResolver.apply(rows,List.of(),change(Operation.EXCLUDE,"客户一",q),catalog,USER1));
        assertEquals(2,SelectionResolver.apply(rows,List.of(),change(Operation.EXCLUDE,"客户一",Quantifier.ALL),catalog,USER1).size());
    }
    @Test void typedEntityPrefixesResolveButNeverOverrideLiteralNameCollisions() {
        var rows=List.of(row(RECEIVABLE,"a","one","测试企业",List.of("测试简称")),row(RECEIVABLE,"b","one","测试企业",List.of("测试简称")));
        for(String mention:List.of("客户测试企业","交易对方测试简称")) {
            var selected=SelectionResolver.apply(rows,List.of(),change(Operation.EXCLUDE,mention,Quantifier.ALL),catalog,USER1);
            assertEquals(2,selected.size());
            assertEquals(List.of(),SelectionResolver.apply(rows,selected,change(Operation.RESTORE,mention,Quantifier.ALL),catalog,USER1));
        }
        var collision=List.of(rows.get(0),row(RECEIVABLE,"c","other","客户测试企业",List.of()));
        assertThrows(ApiException.class,()->SelectionResolver.apply(collision,List.of(),change(Operation.EXCLUDE,"客户测试企业",Quantifier.ALL),catalog,USER1));
        // 称谓本身可以是合法名称的一部分；不做递归去词，不把缺失名称降级到摘要。
        var literal=List.of(row(RECEIVABLE,"d","literal","客户服务中心",List.of()));
        assertEquals(1,SelectionResolver.apply(literal,List.of(),change(Operation.EXCLUDE,"客户服务中心",Quantifier.ALL),catalog,USER1).size());
        assertThrows(ApiException.class,()->SelectionResolver.apply(rows,List.of(),change(Operation.EXCLUDE,"客户客户测试企业",Quantifier.ALL),catalog,USER1));
    }
    @Test void documentKindPrefixIsExactAndAmbiguousIdentifiersRemainRejected() {
        var rows=List.of(candidate(RECEIVABLE,"a","T-900","A","摘要"));
        for(String prefix:List.of("单据","发票","发票号","订单号")) {
            var selection=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of(prefix+"T-900"),prefix+"T-900",List.of(),SelectorKind.DOCUMENT,Quantifier.ONE);
            assertEquals(List.of(new RecordKey(RECEIVABLE,"a")),SelectionResolver.apply(rows,List.of(),selection,catalog,USER1));
        }
        var collision=List.of(rows.get(0),candidate(RECEIVABLE,"b","发票T-900","A","摘要"));
        var selection=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("发票T-900"),"发票T-900",List.of(),SelectorKind.DOCUMENT,Quantifier.ALL);
        assertThrows(ApiException.class,()->SelectionResolver.apply(collision,List.of(),selection,catalog,USER1));
    }
    @Test void customerDataMissingCannotFallBackToLabelAndLaterFailureCannotPartiallyApply() {
        var noIdentity=List.of(candidate(RECEIVABLE,"a","D1","A","客户一"));
        assertThrows(ApiException.class,()->SelectionResolver.apply(noIdentity,List.of(),change(Operation.EXCLUDE,"客户一",Quantifier.ALL),catalog,USER1));
        var rows=List.of(row(RECEIVABLE,"a","one","客户一",List.of()));
        var previous=List.<RecordKey>of();
        var multiple=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("客户一","缺失客户"),"排除客户一和缺失客户",List.of(),SelectorKind.COUNTERPARTY,Quantifier.ALL);
        assertThrows(ApiException.class,()->SelectionResolver.apply(rows,previous,multiple,catalog,USER1));
        assertTrue(previous.isEmpty());
        assertThrows(ApiException.class,()->SelectionResolver.apply(rows,previous,change(Operation.EXCLUDE,"客户一",Quantifier.ALL),catalog,USER3));
    }
    @Test void malformedIdentityAndAliasesFailClosedAndSnapshotsRoundTrip() {
        assertThrows(ApiException.class,()->CounterpartyRef.fromFacts(Map.of("counterpartyName","客户一")));
        assertThrows(ApiException.class,()->CounterpartyRef.fromFacts(Map.of("counterpartyId","x","counterpartyName","客户一","counterpartyAliases","{}")));
        var entity=new CounterpartyRef("stable","客户一",List.of("简称"));
        assertEquals(entity,CounterpartyRef.fromSnapshot(JsonUtil.toJson(entity)));
        assertNull(CounterpartyRef.fromFacts(Map.of("customerName","仅展示摘要")));
    }
    @Test void equivalentReplacementAccountsForRemovedReportWithoutRedundantOperation() {
        String message="去掉应收报表，最终只保留销售报表";
        var intent=new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("销售报表"),"只保留销售报表")),List.of(),
                List.of(new ReportConstraint("应收报表",ReportRole.EXCLUDED,"去掉应收报表"),new ReportConstraint("销售报表",ReportRole.INCLUDED,"只保留销售报表")),List.of(),Clarify.NONE);
        var planner=new SemanticPlanner(catalog);var state=new DialogueState();new IntentCodec().validate(intent,message);
        planner.requireCoverage(state,intent,planner.mentions(USER1,message));planner.merge(USER1,state,intent);
        assertEquals(List.of(SALES),state.getDesired().reportIds());
        var wrong=new SemanticIntent(1,Action.PREVIEW,intent.scopeChanges(),List.of(),List.of(new ReportConstraint("应收报表",ReportRole.INCLUDED,"应收报表")),List.of(),Clarify.NONE);
        var unchanged=state.getDesired();assertThrows(ApiException.class,()->planner.merge(USER1,state,wrong));assertEquals(unchanged,state.getDesired());
    }
    @Test void recordQualifierCannotAlsoJustifyReplacingReportScope() {
        String message="应收报表排除某客户的全部记录";
        var records=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("某客户"),message,List.of("应收报表"),SelectorKind.COUNTERPARTY,Quantifier.ALL);
        var invented=new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("应收报表"),message);
        assertThrows(IntentCodec.InvalidOutput.class,()->new IntentCodec().validate(new SemanticIntent(1,Action.PREVIEW,List.of(invented,records),List.of(),Clarify.NONE),message));
        String explicit="只查询应收报表，排除某客户的全部记录";
        var scope=new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("应收报表"),"只查询应收报表");
        var selection=new ScopeChange(Target.RECORDS,Operation.EXCLUDE,List.of("某客户"),explicit,List.of("应收报表"),SelectorKind.COUNTERPARTY,Quantifier.ALL);
        assertDoesNotThrow(()->new IntentCodec().validate(new SemanticIntent(1,Action.PREVIEW,List.of(scope,selection),List.of(),Clarify.NONE),explicit));
    }
    @Test void documentPrefixesDoNotBecomeReportTermsAndDraftRepairNeverMutatesState() {
        var planner=new SemanticPlanner(catalog);
        for(String doc:List.of("AR-271","AR/2026/22","SO-AR-100"))
            assertEquals(List.of("应收"),planner.mentions(USER1,"排除应收中发票"+doc));
        assertEquals(List.of("ar"),planner.mentions(USER1,"查询AR"));
        var state=new DialogueState();var before=JsonUtil.toJson(state);
        var invalid=new SemanticIntent(1,Action.PREVIEW,List.of(new ScopeChange(Target.REPORTS,Operation.REPLACE,List.of("销售报表"),"仅销售报表"),
                change(Operation.EXCLUDE,"客户一",Quantifier.ALL)),List.of(),Clarify.NONE);
        assertThrows(IntentCodec.InvalidOutput.class,()->planner.validateModelDraft(USER1,state,invalid));
        assertEquals(before,JsonUtil.toJson(state));
    }
}
