package com.example.report.operations;

import com.example.report.common.*;
import com.example.report.dispatch.*;
import com.example.report.entity.*;
import com.example.report.mapper.DispatchPreviewMapper;
import com.example.report.permission.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class OperationsWorkbench {
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final PreviewService previews;
    private final DispatchPreviewMapper previewMapper;
    private final PlanService plans;
    private final DispatchService dispatch;
    private final OperationsAudit audit;
    private final org.springframework.transaction.support.TransactionOperations tx;
    public OperationsWorkbench(JdbcTemplate jdbc,PermissionService permissions,PreviewService previews,
            DispatchPreviewMapper previewMapper,PlanService plans,DispatchService dispatch,OperationsAudit audit,
            org.springframework.transaction.support.TransactionOperations tx) {
        this.jdbc=jdbc;this.permissions=permissions;this.previews=previews;this.previewMapper=previewMapper;
        this.plans=plans;this.dispatch=dispatch;this.audit=audit;
        this.tx=tx;
    }
    public record Page(List<Map<String,Object>> rows,String nextCursor) { }
    public Page list(CurrentUser admin,String after) {
        OperationsPolicy.requireAdmin(admin);
        var rows=jdbc.queryForList("""
                SELECT id,preview_id,user_id,status,status_reason,item_count,success_count,failed_count,expires_at,updated_at
                FROM dispatch_plan WHERE tenant_id=? AND id>?
                AND COALESCE(status_reason,'')<>'OPERATOR_CLOSED'
                AND (status IN ('REVIEW_REQUIRED','EXPIRED','CANCELLED') OR (status='EXECUTED' AND failed_count>0)
                  OR (status='PENDING' AND expires_at<=NOW())) ORDER BY id LIMIT 51
                """,admin.tenantId(),after==null?"":after);
        boolean more=rows.size()>50;
        var batch=rows.subList(0,Math.min(50,rows.size()));
        var visible=new ArrayList<Map<String,Object>>();
        for(var row:batch) {
            try { readable(admin,row.get("preview_id").toString()); visible.add(row); }
            catch(ApiException denied) { /* Do not expose report or company scope outside the operator's grants. */ }
        }
        audit.record(admin,"WORKBENCH_READ","plans","SUCCESS",null);
        return new Page(visible,more?batch.get(batch.size()-1).get("id").toString():null);
    }
    private void readable(CurrentUser admin,String previewId) {
        DispatchPreview p=previewMapper.selectById(previewId);
        if(p==null || !admin.tenantId().equals(p.getTenantId())) throw ApiException.notFound("任务不存在");
        previews.requireReadable(admin,p);
    }
    private CurrentUser owner(CurrentUser admin,String planId) {
        OperationsPolicy.requireAdmin(admin);
        var rows=jdbc.queryForList("SELECT user_id,preview_id FROM dispatch_plan WHERE tenant_id=? AND id=?",admin.tenantId(),planId);
        if(rows.isEmpty()) throw ApiException.notFound("任务不存在");
        readable(admin,rows.get(0).get("preview_id").toString());
        CurrentUser owner=permissions.resolve(rows.get(0).get("user_id").toString());
        if(!admin.tenantId().equals(owner.tenantId())) throw ApiException.notFound("任务不存在");
        return owner;
    }
    public Object items(CurrentUser admin,String planId,int page) {
        var owner=owner(admin,planId);
        Object result=plans.pageOwned(owner,planId,page,50);
        audit.record(admin,"WORKBENCH_ITEMS",planId,"SUCCESS",null);
        return result;
    }
    public Object act(CurrentUser admin,String planId,String action,String reason) {
        OperationsPolicy.requireReason(reason);
        if(!Set.of("retry-failed","reconcile","cancel","close").contains(action)) throw new ApiException("不支持的操作");
        CurrentUser owner=owner(admin,planId);
        audit.record(admin,"WORKBENCH_"+action,planId,"STARTED",reason);
        Object result;
        try {
            result=switch(action) {
                case "retry-failed" -> dispatch.retryFailed(owner,planId);
                case "reconcile" -> dispatch.reconcile(owner,planId);
                case "close" -> closeKnownFailure(admin,owner,planId,reason);
                default -> dispatch.cancel(owner,planId);
            };
        } catch(RuntimeException failure) {
            audit.record(admin,"WORKBENCH_"+action,planId,"FAILED",reason);
            throw failure;
        }
        audit.record(admin,"WORKBENCH_"+action,planId,"SUCCESS",reason);
        return result;
    }
    private Object closeKnownFailure(CurrentUser actor,CurrentUser owner,String id,String reason) {
        return tx.execute(status -> {
            var rows=jdbc.queryForList("SELECT status FROM dispatch_plan WHERE tenant_id=? AND user_id=? AND id=? FOR UPDATE",owner.tenantId(),owner.userId(),id);
            if(rows.isEmpty()) throw ApiException.notFound("任务不存在");
            String state=rows.get(0).get("status").toString();
            if(!Set.of("EXECUTED","EXPIRED","CANCELLED").contains(state)) throw new ApiException(409,"请先完成核对，不能关闭在途或结果不明的任务");
            long unknown=jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan_item WHERE plan_id=? AND status='UNKNOWN'",Long.class,id);
            if(unknown>0) throw new ApiException(409,"仍有结果不明的条目，请先核对");
            jdbc.update("UPDATE dispatch_plan_item SET status='SKIPPED',error_code='OPERATOR_CLOSED',error_message='运营关闭，未再次派单',updated_at=NOW(3) WHERE plan_id=? AND status='FAILED'",id);
            jdbc.update("UPDATE dispatch_plan SET status_reason='OPERATOR_CLOSED',updated_at=NOW(3) WHERE id=?",id);
            audit.record(actor,"WORKBENCH_CLOSE_COMMIT",id,"SUCCESS",reason);
            return Map.of("status",state,"reason","OPERATOR_CLOSED");
        });
    }
}
