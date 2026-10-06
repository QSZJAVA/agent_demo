package com.example.report.rule;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.FactRow;
import com.example.report.catalog.query.ReportQueryAdapter;
import com.example.report.common.ApiException;
import com.example.report.entity.DispatchRule;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用多页合成来源验证完整性、分页契约和资源预算；不代表真实 ERP 的容量验收。 */
class CandidateScanBoundaryTest {
    private final ReportQueryAdapter adapter=mock(ReportQueryAdapter.class);
    private final CatalogEntry report=mock(CatalogEntry.class);
    private final RuleCache rules=mock(RuleCache.class);
    private final DispatchCandidateService service=new DispatchCandidateService(rules,new RuleEngine());
    CandidateScanBoundaryTest() {
        when(report.tenantId()).thenReturn("T001");when(report.reportId()).thenReturn("sales");
        when(report.usable()).thenReturn(true);when(report.adapter()).thenReturn(adapter);when(report.fields()).thenReturn(List.of());
        var rule=new DispatchRule();rule.setExpression("amount > 0");rule.setVersion(1);
        when(rules.find("T001","sales","A")).thenReturn(Optional.of(rule));
    }
    private static FactRow row(int id,String company) {
        return new FactRow(Integer.toString(id),"SO"+id,company,"记录",BigDecimal.ONE,null,Map.of("amount",id%1000==0?1:0));
    }
    private static List<FactRow> page(int start,int count) {
        return IntStream.range(start,start+count).mapToObj(i->row(i,"A")).toList();
    }
    private List<Candidate> scan() { return service.findCandidates("T001",Set.of("A"),List.of(report)); }

    @Test void lowHitRateScanVisitsAllPagesAndReturnsExactMatches() {
        var calls=new AtomicInteger();
        when(adapter.pendingRowsAfterWithRule(anyString(),anySet(),nullable(String.class),eq(500),anyString())).thenAnswer(call->{
            calls.incrementAndGet();String after=call.getArgument(2);int start=after==null?0:Integer.parseInt(after)+1;
            return page(start,Math.min(500,20000-start));
        });
        long started=System.nanoTime();var result=scan();
        assertEquals(IntStream.range(0,20).mapToObj(i->Integer.toString(i*1000)).toList(),result.stream().map(Candidate::recordId).toList());
        assertEquals(41,calls.get());
        System.out.println("SYNTHETIC_SCAN rows=20000 pages="+calls.get()+" matches="+result.size()+" elapsedMs="+(System.nanoTime()-started)/1000000);
    }
    @Test void budgetIsGlobalAcrossPagesEvenWithFewMatches() {
        ReflectionTestUtils.setField(service,"maxScannedRows",600);
        when(adapter.pendingRowsAfterWithRule(anyString(),anySet(),nullable(String.class),eq(500),anyString())).thenReturn(page(0,500),page(500,500));
        assertEquals(422,assertThrows(ApiException.class,this::scan).getCode());
    }
    @Test void repeatedCursorAndOverlappingPagesAreRejected() {
        when(adapter.pendingRowsAfterWithRule(anyString(),anySet(),nullable(String.class),eq(500),anyString())).thenReturn(page(0,500),page(0,500));
        assertEquals(502,assertThrows(ApiException.class,this::scan).getCode());
        when(adapter.pendingRowsAfterWithRule(anyString(),anySet(),nullable(String.class),eq(500),anyString())).thenReturn(page(0,500),page(499,500));
        assertEquals(502,assertThrows(ApiException.class,this::scan).getCode());
    }
    @Test void invalidPageSizeIdentityAndCompanyNeverProduceSuccess() {
        for(var invalid:List.of(page(0,501),List.of(row(1,"B")),List.of(row(1,"A"),row(1,"A")),List.of(new FactRow(" ","SO","A","",null,null,Map.of())))) {
            when(adapter.pendingRowsAfterWithRule(anyString(),anySet(),nullable(String.class),eq(500),anyString())).thenReturn(invalid);
            assertEquals(502,assertThrows(ApiException.class,this::scan).getCode());
        }
    }
    @Test void expiredBudgetAndCancellationFailEvenWhenThePageWouldBeEmpty() {
        ReflectionTestUtils.setField(service,"maxScanSeconds",1);
        when(adapter.pendingRowsAfterWithRule(anyString(),anySet(),nullable(String.class),eq(500),anyString())).thenAnswer(call->{Thread.sleep(1100);return List.of();});
        assertEquals(408,assertThrows(ApiException.class,this::scan).getCode());
        try {Thread.currentThread().interrupt();assertEquals(408,assertThrows(ApiException.class,this::scan).getCode());}
        finally {Thread.interrupted();}
    }
}
