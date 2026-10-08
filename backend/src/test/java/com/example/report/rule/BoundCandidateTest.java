package com.example.report.rule;

import com.example.report.catalog.*;
import com.example.report.catalog.query.*;
import com.example.report.common.ApiException;
import com.example.report.dispatch.*;
import com.example.report.entity.DispatchRule;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 精确对象重新核验的业务回归：规则、来源状态、公司或完整性任一失效都不能部分返回合格对象。 */
class BoundCandidateTest {
    private final RuleCache rules=mock(RuleCache.class);
    private final ReportQueryAdapter adapter=mock(ReportQueryAdapter.class);
    private final DispatchCandidateService service=new DispatchCandidateService(rules,new RuleEngine());
    private final CatalogEntry report=new CatalogEntry("T001","r","sales","销售","sales",null,"STANDARD","{}",true,"PUBLISHED",1,1L,"p",1,null,null,null,"test",LocalDateTime.now(),List.of(),adapter,null);
    @org.junit.jupiter.api.BeforeEach void fields(){when(adapter.fields()).thenReturn(List.of(new FieldInfo("amount","decimal","金额")));}
    private RecordTarget target(String id){return new RecordTarget(new RecordKey("r",id),"A");}
    private FactRow row(String id,String company,int amount){return new FactRow(id,"D"+id,company,"产品",BigDecimal.valueOf(amount),LocalDate.of(2026,1,1),Map.of("amount",amount));}
    private void rule(String expression) {
        var rule=new DispatchRule();rule.setId(1L);rule.setVersion(2);rule.setName("金额规则");rule.setExpression(expression);
        when(rules.find("T001","r","A")).thenReturn(Optional.of(rule));
    }
    private List<Candidate> find(RecordTarget... targets){return service.findBoundCandidates("T001",Set.of("A","B"),List.of(report),List.of(targets),n->{});}
    @Test void exactIdentityAndOrderArePreservedWithCurrentRuleEvidence() {
        rule("amount > 20");when(adapter.pendingRowsByIds(eq("T001"),anyCollection())).thenReturn(List.of(row("2","A",40),row("1","A",80)));
        var result=find(target("1"),target("2"));assertEquals(List.of("1","2"),result.stream().map(Candidate::recordId).toList());
        assertEquals(2,result.get(0).ruleVersion());verify(adapter).pendingRowsByIds("T001",List.of("1","2"));
        verify(adapter,never()).pendingRows(anyString(),anySet());
    }
    @Test void missingDispatchedChangedCompanyOrExtraRowRejectsWholeGroup() {
        rule("amount > 20");
        for(var rows:List.of(List.of(row("1","A",40)),List.of(row("1","A",40),row("2","B",40)),
                List.of(row("1","A",40),row("3","A",40)),List.of(row("1","A",40),row("1","A",40)))) {
            when(adapter.pendingRowsByIds(eq("T001"),anyCollection())).thenReturn(rows);
            assertThrows(ApiException.class,()->find(target("1"),target("2")));
        }
    }
    @Test void oneIneligibleRecordOrBrokenRuleCannotProduceSubset() {
        rule("amount > 20");when(adapter.pendingRowsByIds(eq("T001"),anyCollection())).thenReturn(List.of(row("1","A",80),row("2","A",10)));
        assertEquals(422,assertThrows(ApiException.class,()->find(target("1"),target("2"))).getCode());
        rule("string.contains(missing, 'x')");assertThrows(ApiException.class,()->find(target("1"),target("2")));
    }
    @Test void duplicateIdentityAndUnauthorizedScopeAreRejectedBeforeRead() {
        assertThrows(ApiException.class,()->find(target("1"),target("1")));
        assertThrows(ApiException.class,()->find(new RecordTarget(new RecordKey("other","1"),"A")));
        assertThrows(ApiException.class,()->find(new RecordTarget(new RecordKey("r","1"),"C")));verifyNoInteractions(adapter);
    }
}
