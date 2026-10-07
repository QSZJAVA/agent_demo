package com.example.business;

import com.example.report.permission.CurrentUser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定演示流程的权限、终态、当前审批人和派单关联验证；不代表真实工单系统已接入。 */
class DemoWorkOrderProviderTest {
    final DemoWorkOrderProvider provider=new DemoWorkOrderProvider();
    final CurrentUser user=new CurrentUser("T001","u","演示用户",Set.of("A"),Set.of("report:sales"),false);
    @Test void fixturesRespectTenantCompanyAndReportAndNeverAdvanceOnRead() {
        var rows=provider.read(user,Set.of("rpt-sales-order"),Set.of("A"),List.of());
        assertEquals(2,rows.size());assertEquals(rows,provider.read(user,Set.of("rpt-sales-order"),Set.of("A"),List.of()));
        assertTrue(rows.stream().allMatch(r->"A".equals(r.get("companyCode")) && Boolean.TRUE.equals(r.get("simulated"))));
        assertTrue(provider.read(new CurrentUser("T002","u","另一租户",Set.of("A"),Set.of("*"),true),Set.of("rpt-sales-order"),Set.of("A"),List.of()).isEmpty());
    }
    @Test void completedAndRejectedOrdersHaveNoCurrentApprover() {
        var rows=provider.read(user,Set.of("rpt-sales-order","rpt-expense-claim"),Set.of("A"),List.of());
        for(var row:rows)if(Set.of("已完成","已驳回").contains(row.get("status")))assertNull(row.get("assignee"));
        var active=rows.stream().filter(r->"WO-DEMO-001".equals(r.get("orderId"))).findFirst().orElseThrow();
        assertEquals("部门审批",active.get("stage"));assertEquals("林主管（演示）",active.get("assignee"));
    }
    @Test void onlySuccessfulActualRequestsProduceLinkedInitialApproval() {
        var good=new LinkedHashMap<String,Object>();good.put("status","成功");good.put("requestId","request-1");good.put("reportId","rpt-sales-order");good.put("companyCode","A");good.put("docNo","SO-LIVE");good.put("createdDate","2026-10-06");
        var failed=new LinkedHashMap<>(good);failed.put("status","结果未知");failed.put("requestId","request-2");
        var rows=provider.read(user,Set.of("rpt-sales-order"),Set.of("A"),List.of(good,failed));
        var actual=rows.stream().filter(r->"实际演示派单关联".equals(r.get("source"))).toList();assertEquals(1,actual.size());
        assertEquals("WO-request-1",actual.get(0).get("orderId"));assertEquals("待审批",actual.get(0).get("status"));
    }
}
