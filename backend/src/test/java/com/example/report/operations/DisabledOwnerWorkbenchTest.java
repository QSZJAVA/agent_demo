package com.example.report.operations;
import com.example.report.common.ApiException;
import com.example.report.dispatch.*;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPlan;
import com.example.report.mapper.DispatchPreviewMapper;
import com.example.report.permission.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
/** 原操作者已禁用时的运营代读与恢复边界；读取、核对和关闭不能借此重新激活账号或授权重试。 */
class DisabledOwnerWorkbenchTest {
    @Test void disabledOwnerCanBeReadReconciledAndClosedButCannotBeRetried() {
        var jdbc=mock(JdbcTemplate.class);var permissions=mock(PermissionService.class);
        when(permissions.resolve("disabled")).thenThrow(ApiException.forbidden("disabled account"));
        when(jdbc.queryForList("SELECT user_id,preview_id FROM dispatch_plan WHERE tenant_id=? AND id=?","T001","plan"))
                .thenReturn(List.of(Map.of("user_id","disabled","preview_id","preview")));
        when(jdbc.queryForList("SELECT status FROM dispatch_plan WHERE tenant_id=? AND user_id=? AND id=? FOR UPDATE","T001","disabled","plan"))
                .thenReturn(List.of(Map.of("status","EXECUTED")));
        when(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan_item WHERE plan_id=? AND status='UNKNOWN'",Long.class,"plan")).thenReturn(0L);
        var preview=new DispatchPreview();preview.setTenantId("T001");
        var mapper=mock(DispatchPreviewMapper.class);when(mapper.selectById("preview")).thenReturn(preview);
        var previews=mock(PreviewService.class);var plans=mock(PlanService.class);
        var plan=new DispatchPlan();plan.setId("plan");plan.setPreviewId("preview");plan.setStatus("EXECUTED");plan.setItemCount(0);plan.setExcludeJson("[]");
        when(plans.findOwned(any(),eq("plan"))).thenReturn(Optional.of(plan));
        when(plans.pageOwned(any(),eq("plan"),eq(1),eq(50))).thenReturn(List.of());
        var dispatch=mock(DispatchService.class);var audit=mock(OperationsAudit.class);
        var workbench=new OperationsWorkbench(jdbc,permissions,previews,mapper,plans,dispatch,audit,TransactionOperations.withoutTransaction());
        var admin=new CurrentUser("T001","admin","Admin",Set.of("A"),Set.of("*"),true);
        assertEquals("plan",workbench.readPlan(admin,"plan").planId());
        workbench.items(admin,"plan",1);
        workbench.act(admin,"plan","reconcile","核对离职账号请求");
        workbench.act(admin,"plan","close","关闭明确失败");
        verify(dispatch).reconcileForOperator(admin,"disabled","plan");
        verify(permissions,never()).resolve(anyString());
        verify(audit).record(admin,"WORKBENCH_CLOSE_COMMIT","plan","SUCCESS","关闭明确失败");
        verify(audit).record(admin,"WORKBENCH_PLAN_READ","plan","SUCCESS",null);
        assertThrows(ApiException.class,()->workbench.act(admin,"plan","retry-failed","禁止禁用账号重发"));
        verify(dispatch,never()).retryFailed(any(),anyString());
        var regular=new CurrentUser("T001","regular","",Set.of("A"),Set.of("*"),false);
        assertThrows(ApiException.class,()->workbench.act(regular,"plan","reconcile","越权"));
    }
}
