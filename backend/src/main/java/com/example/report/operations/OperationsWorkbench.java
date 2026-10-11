package com.example.report.operations;

import com.example.report.common.*;
import com.example.report.dispatch.*;
import com.example.report.entity.*;
import com.example.report.mapper.DispatchPreviewMapper;
import com.example.report.permission.*;
import com.example.report.agent.PlanPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 租户管理员处理异常派单清单的工作台；管理员仍须具有对应公司和报表数据权限。
 * 重试要求重新确认原操作者的当前授权；代核对、取消和关闭保留真实操作人审计。结果不明或仍在执行的任务不得直接关闭。
 */
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
    /**
     * 按当前授权范围查询的有界分页结果。
     * @param rows 本页按当前权限过滤后的记录集合
     * @param nextCursor 下一页查询游标；没有后续数据时为空
     */
    public record Page(List<Map<String,Object>> rows,String nextCursor) { }
    /**
     * 按租户分页读取异常清单，逐条按管理员当前数据权限过滤。游标取扫描批次末尾，过滤后仍可继续下一页，避免重复扫描。
     */
    public Page list(CurrentUser admin,String after) {
        OperationsPolicy.requireAdmin(admin);
        var rows=jdbc.queryForList("""
                SELECT id,preview_id,user_id,status,status_reason,item_count,success_count,failed_count,expires_at,updated_at
                FROM dispatch_plan WHERE tenant_id=? AND id>?
                AND COALESCE(status_reason,'')<>'OPERATOR_CLOSED'
                AND (status IN ('EXECUTING','REVIEW_REQUIRED','EXPIRED','CANCELLED') OR (status='EXECUTED' AND failed_count>0)
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
    /**
     * 验证管理员与原清单的数据范围。重试须读取原用户当前授权；只读、取消和关闭使用管理员的当前可见范围，不依赖原账号是否可登录。
     */
    private CurrentUser owner(CurrentUser admin,String planId,boolean requireActive) {
        OperationsPolicy.requireAdmin(admin);
        var rows=jdbc.queryForList("SELECT user_id,preview_id FROM dispatch_plan WHERE tenant_id=? AND id=?",admin.tenantId(),planId);
        if(rows.isEmpty()) throw ApiException.notFound("任务不存在");
        readable(admin,rows.get(0).get("preview_id").toString());
        String ownerId=rows.get(0).get("user_id").toString();
        // Administrative reading/closing/cancelling must not depend on the owner's account lifecycle.
        CurrentUser owner=requireActive ? permissions.resolve(ownerId)
                : new CurrentUser(admin.tenantId(),ownerId,admin.displayName(),admin.companies(),admin.permissions(),true);
        if(!admin.tenantId().equals(owner.tenantId())) throw ApiException.notFound("任务不存在");
        return owner;
    }
    public Object items(CurrentUser admin,String planId,int page) {
        var owner=owner(admin,planId,false);
        Object result=plans.pageOwned(owner,planId,page,50);
        audit.record(admin,"WORKBENCH_ITEMS",planId,"SUCCESS",null);
        return result;
    }
    /**
     * 读取当前授权范围内的清单摘要和最多50条样本；代读不依赖原操作者账号仍可登录。
     * 只在返回副本中投影到期状态，写入仅限真实管理员访问审计；不认领清单、不调用模型或派单网关。
     * @param admin 当前已认证管理员，仍须覆盖清单的租户、公司与报表范围
     * @param planId 已有清单标识，跨租户或越权时不可读取
     * @return 最新清单标识、状态、总数及有界记录样本
     */
    public PlanPayload readPlan(CurrentUser admin,String planId) {
        return tx.execute(transaction -> {
            CurrentUser owner=owner(admin,planId,false);
            var plan=plans.findOwned(owner,planId).orElseThrow(()->ApiException.notFound("任务不存在"));
            var projected=JsonUtil.MAPPER.convertValue(plan,DispatchPlan.class);
            if(DispatchPlan.PENDING.equals(projected.getStatus())) {
                var preview=previewMapper.selectById(projected.getPreviewId());
                var now=java.time.LocalDateTime.now();
                // 代读身份的授权范围可能与原确认人不同，不能拿它的权限指纹把原清单判为版本失效。
                // 这里只投影已过期或已作废的预览/清单；真正执行仍由原确认及执行版本校验决定。
                if(!projected.getExpiresAt().isAfter(now) || preview==null || !DispatchPreview.ACTIVE.equals(preview.getStatus())
                        || !preview.getExpiresAt().isAfter(now)) {
                    projected.setStatus(DispatchPlan.EXPIRED);
                }
            }
            var result=PlanPayload.of(new PlanSnapshot(projected,plans.pageOwned(owner,planId,1,50)));
            audit.record(admin,"WORKBENCH_PLAN_READ",planId,"SUCCESS",null);
            return result;
        });
    }
    public Object act(CurrentUser admin,String planId,String action,String reason) {
        return act(admin,planId,action,reason,null);
    }
    /**
     * 任务入队前验证动作白名单、操作原因与授权；执行时还需重新验证，不能把入队授权视为永久许可。
     */
    public void validateAction(CurrentUser admin,String planId,String action,String reason) {
        OperationsPolicy.requireReason(reason);
        if(!Set.of("retry-failed","reconcile","cancel","close").contains(action)) throw new ApiException("不支持的操作");
        owner(admin,planId,"retry-failed".equals(action));
    }
    /**
     * 记录开始、成功或失败审计后执行运维动作；重试绑定异步提交版本，核对不产生新的业务写入。
     */
    public Object act(CurrentUser admin,String planId,String action,String reason,Long expectedVersion) {
        OperationsPolicy.requireReason(reason);
        if(!Set.of("retry-failed","reconcile","cancel","close").contains(action)) throw new ApiException("不支持的操作");
        CurrentUser owner=owner(admin,planId,"retry-failed".equals(action));
        audit.record(admin,"WORKBENCH_"+action,planId,"STARTED",reason);
        Object result;
        try {
            result=switch(action) {
                case "retry-failed" -> expectedVersion==null ? dispatch.retryFailed(owner,planId) : dispatch.retryFailed(owner,planId,expectedVersion);
                case "reconcile" -> dispatch.reconcileForOperator(admin,owner.userId(),planId);
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
    /**
     * 持有清单锁后仅关闭已结束且无 UNKNOWN 的失败；保留幂等请求号，将不再重试的失败置 SKIPPED，不得借关闭绕过结果核对。
     */
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
