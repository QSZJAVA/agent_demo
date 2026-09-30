package com.example.report.operations;
import com.example.report.common.ApiException;
import com.example.report.dispatch.*;
import com.example.report.entity.DispatchPreview;
import com.example.report.mapper.DispatchPreviewMapper;
import com.example.report.permission.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
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
        var dispatch=mock(DispatchService.class);var audit=mock(OperationsAudit.class);
        var workbench=new OperationsWorkbench(jdbc,permissions,previews,mapper,plans,dispatch,audit,TransactionOperations.withoutTransaction());
        var admin=new CurrentUser("T001","admin","Admin",Set.of("A"),Set.of("*"),true);
        workbench.items(admin,"plan",1);
        workbench.act(admin,"plan","reconcile","核对离职账号请求");
        workbench.act(admin,"plan","close","关闭明确失败");
        verify(dispatch).reconcileForOperator(admin,"disabled","plan");
        verify(permissions,never()).resolve(anyString());
        verify(audit).record(admin,"WORKBENCH_CLOSE_COMMIT","plan","SUCCESS","关闭明确失败");
        assertThrows(ApiException.class,()->workbench.act(admin,"plan","retry-failed","禁止禁用账号重发"));
        verify(dispatch,never()).retryFailed(any(),anyString());
        var regular=new CurrentUser("T001","regular","",Set.of("A"),Set.of("*"),false);
        assertThrows(ApiException.class,()->workbench.act(regular,"plan","reconcile","越权"));
    }
}
