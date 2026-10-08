package com.example.business;

import com.example.report.assistant.*;
import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.*;
import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 长字段事实的独立资源预算测试，确认没有达到行数上限时仍会按字节量安全停止完整统计。 */
class BusinessReadBudgetTest {
    @Test void largeFieldDataFailsAtByteBudgetBeforeRowBudget() {
        var queries=mock(BusinessQueries.class);var adapter=mock(ReportQueryAdapter.class);var entry=mock(CatalogEntry.class);
        var user=new CurrentUser("T001","u","用户",Set.of("A"),Set.of("*"),false);
        when(queries.catalog(user)).thenReturn(List.of(Map.of("reportId","r")));
        when(queries.require(user,"r",false)).thenReturn(entry);when(entry.reportId()).thenReturn("r");when(entry.reportName()).thenReturn("长字段报表");
        when(entry.fields()).thenReturn(List.of(new FieldInfo("detail","string","长字段")));when(entry.adapter()).thenReturn(adapter);
        String text="x".repeat(4096);var observed=new java.util.concurrent.atomic.AtomicInteger();
        when(adapter.dataRowsAfter(eq("T001"),eq(Set.of("A")),any(),anyInt())).thenAnswer(call->{
            String after=call.getArgument(2);int start=after==null?0:Integer.parseInt(after);int size=call.getArgument(3);var rows=new ArrayList<ReportDataRow>();
            for(int i=start+1;i<=Math.min(5000,start+size);i++)rows.add(new ReportDataRow(new FactRow(String.valueOf(i),"D"+i,"A","长字段",null,null,Map.of("detail",text)),"未派单"));
            observed.addAndGet(rows.size());return rows;
        });
        var service=new BusinessReadService(queries,mock(NamedParameterJdbcTemplate.class),mock(WorkOrderProvider.class),10000,120,new BusinessQueryProperties(),mock(DispatchEligibilityService.class));
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.SUMMARY,List.of("r"),"A",List.of(),null,false,1,20,null);
        var failure=assertThrows(ApiException.class,()->service.query(user,query));assertTrue(failure.getMessage().contains("16MiB"));assertTrue(observed.get()<5000);
    }
}
