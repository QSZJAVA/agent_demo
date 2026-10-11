package com.example.report.workspace;

import com.example.report.common.*;
import com.example.report.operations.OperationsAudit;
import com.example.report.permission.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/**
 * 只读快照异步导出队列；持久化幂等准入、租户隔离认领、令牌续租和条件回写，重启只重建同一不可变文件。
 * 取消使令牌失效；下载重新验证身份、目录和快照，后台任务不会绕过业务权限或执行派单。
 */
@Service
public class QueryExportService {
    private final JdbcTemplate jdbc;
    private final QuerySnapshotStore snapshots;
    private final QueryWorkspaceService workspace;
    private final PermissionService permissions;
    private final OperationsAudit audit;
    private final TransactionTemplate tx;
    public QueryExportService(JdbcTemplate jdbc,QuerySnapshotStore snapshots,QueryWorkspaceService workspace,PermissionService permissions,OperationsAudit audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.snapshots=snapshots;this.workspace=workspace;this.permissions=permissions;this.audit=audit;tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    /**
     * 导出最小状态，不包含文件或原始凭据。
     * @param id 任务主键
     * @param runId 不可变查询运行标识
     * @param status 当前任务状态
     * @param version 状态版本，不受续租影响
     * @param progressRows 已生成记录数
     * @param message 状态说明，空表示无补充说明
     * @param expiresAt 文件UTC有效期
     * @param createdAt 创建UTC时间
     * @param contentHash 完整文件SHA-256，未生成时为空
     */
    public record Job(String id,String runId,String status,long version,int progressRows,String message,String expiresAt,String createdAt,String contentHash) { }
    /** 同用户同键只能对应同一个快照；先验证当前权限，再回放已有任务，任何状态都不自动另建任务。 */
    public Job submit(CurrentUser user,String runId,String key) {
        if(key==null || !key.matches("[a-zA-Z0-9_-]{16,128}"))throw new ApiException(422,"导出需要稳定幂等键");var run=workspace.require(user,runId);
        return tx.execute(status->{snapshots.lock(user.tenantId());
            var prior=jdbc.queryForList("SELECT * FROM query_export_job WHERE tenant_id=? AND user_id=? AND idempotency_key=?",user.tenantId(),user.userId(),key);
            if(!prior.isEmpty()){if(!runId.equals(prior.get(0).get("run_id")))throw new ApiException(409,"导出幂等键已用于另一份查询");return view(prior.get(0));}
            if(jdbc.queryForObject("SELECT COUNT(*) FROM query_export_job WHERE tenant_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,user.tenantId())>=32
                    || jdbc.queryForObject("SELECT COUNT(*) FROM query_export_job WHERE tenant_id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,user.tenantId(),user.userId())>=4)throw new ApiException(429,"导出队列已满，请等待现有任务结束");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM query_export_job WHERE tenant_id=? AND user_id=? AND created_at>TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(3))",Integer.class,user.tenantId(),user.userId())>=100)throw new ApiException(429,"今日导出数量已达到100次");
            String id=JsonUtil.newId();jdbc.update("INSERT INTO query_export_job(id,tenant_id,user_id,run_id,idempotency_key,expires_at) VALUES (?,?,?,?,?,?)",id,user.tenantId(),user.userId(),runId,key,LocalDateTime.ofInstant(Instant.parse(run.result().snapshot().expiresAt()),ZoneOffset.UTC));
            audit.record(user,"QUERY_EXPORT_SUBMIT",id,"QUEUED",null);return view(owned(user,id));
        });
    }
    /** 读取原任务状态；权限不再有效时不给出关联快照名称或进度。 */
    public Job get(CurrentUser user,String id){var row=owned(user,id);workspace.require(user,row.get("run_id").toString());return view(row);}
    /** 只取消未完成任务，重复取消幂等；已完成文件通过删除快照失效。 */
    public Job cancel(CurrentUser user,String id) {
        var row=owned(user,id);workspace.require(user,row.get("run_id").toString());
        jdbc.update("UPDATE query_export_job SET status='CANCELLED',claim_token=NULL,lease_until=NULL,content=NULL,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3),message='用户取消导出' WHERE id=? AND tenant_id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",id,user.tenantId(),user.userId());
        audit.record(user,"QUERY_EXPORT_CANCEL",id,"SUCCESS",null);return view(owned(user,id));
    }
    /** 下载读取完成后再核验一次权限和有效期，返回受控字节；拒绝路径不返回旧文件。 */
    public byte[] download(CurrentUser user,String id) {
        var row=owned(user,id);workspace.require(user,row.get("run_id").toString());
        if(!"SUCCEEDED".equals(row.get("status")) || !QuerySnapshotStore.instant(row.get("expires_at")).isAfter(Instant.now()))throw new ApiException(409,"导出文件尚未完成或已失效");
        var files=jdbc.queryForList("SELECT content FROM query_export_job WHERE id=? AND tenant_id=? AND user_id=? AND status='SUCCEEDED' AND expires_at>UTC_TIMESTAMP(3)",id,user.tenantId(),user.userId());
        if(files.isEmpty() || files.get(0).get("content")==null)throw new ApiException(410,"导出文件已失效");
        byte[] bytes=(byte[])files.get(0).get("content");workspace.require(user,row.get("run_id").toString());audit.record(user,"QUERY_EXPORT_DOWNLOAD",id,"SUCCESS",null);return bytes;
    }
    /** 每实例只生成一份文件，不在内存堆积队列；短认领事务使用SKIP LOCKED避免多实例重复认领。 */
    @Scheduled(fixedDelayString="${agent.query-export-poll-ms:1000}",initialDelayString="${agent.query-export-initial-ms:3000}")
    public void poll() {
        try {recover();var row=claim();if(row!=null)generate(row);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("查询导出轮询未完成 type={}",failure.getClass().getSimpleName());}
    }
    /** 无持有者的排队项可认领；所有查询都绑定当前部署租户，令牌每次变化。 */
    Map<String,Object> claim(){return tx.execute(status->{var rows=jdbc.queryForList("SELECT * FROM query_export_job WHERE tenant_id=? AND status='QUEUED' AND expires_at>UTC_TIMESTAMP(3) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",permissions.tenantId());
        if(rows.isEmpty())return null;var row=new LinkedHashMap<>(rows.get(0));String token=JsonUtil.newId();
        jdbc.update("UPDATE query_export_job SET status='RUNNING',claim_token=?,attempts=attempts+1,lease_until=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3)),row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",token,row.get("id"));row.put("claim_token",token);return row;});}
    /** 失租只允许重建同一快照文件，最多三次；过期和排队超时明确终止，不无限重试。 */
    void recover() {
        String tenant=permissions.tenantId();
        jdbc.update("UPDATE query_export_job SET status='EXPIRED',claim_token=NULL,content=NULL,content_hash=NULL,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3),message='查询或导出保留期已结束' WHERE tenant_id=? AND status<>'EXPIRED' AND expires_at<=UTC_TIMESTAMP(3)",tenant);
        jdbc.update("UPDATE query_export_job SET status='FAILED',claim_token=NULL,lease_until=NULL,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3),message='导出已超过重建次数或排队时限' WHERE tenant_id=? AND status IN ('RUNNING','QUEUED') AND (created_at<TIMESTAMPADD(MINUTE,-10,UTC_TIMESTAMP(3)) OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(3) AND attempts>=3))",tenant);
        jdbc.update("UPDATE query_export_job SET status='QUEUED',claim_token=NULL,lease_until=NULL,progress_rows=0,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3),message='执行租约中断，等待重建同一快照文件' WHERE tenant_id=? AND status='RUNNING' AND lease_until<=UTC_TIMESTAMP(3) AND attempts<3",tenant);
    }
    /** 生成时逐批续租，权限变化、取消或失租后立即停止；迟到完成不能覆盖新状态或令牌。 */
    void generate(Map<String,Object> job) {
        String id=job.get("id").toString(),token=job.get("claim_token").toString(),runId=job.get("run_id").toString();long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        try {
            CurrentUser user=permissions.resolve(job.get("user_id").toString());if(!user.tenantId().equals(job.get("tenant_id")))throw ApiException.forbidden("导出归属不匹配");
            var run=workspace.require(user,runId);var csv=new QueryCsv(run.result());int processed=0;
            while(processed<run.result().total()) {
                if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)throw new ApiException(504,"导出生成超时");
                renew(id,token,processed);workspace.require(user,runId);int size=Math.min(500,run.result().total()-processed);var rows=snapshots.rows(runId,processed,size);
                if(rows.size()!=size)throw new ApiException(410,"查询明细已不可用，请重新查询");rows.forEach(csv::add);processed+=rows.size();
            }
            renew(id,token,processed);workspace.require(user,runId);byte[] bytes=csv.bytes();
            int changed=jdbc.update("UPDATE query_export_job SET status='SUCCEEDED',content=?,content_hash=?,claim_token=NULL,lease_until=NULL,progress_rows=?,message='完整快照导出完成',row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_until>UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",bytes,hash(bytes),processed,id,token);
            if(changed==1)audit.record(user,"QUERY_EXPORT_FINISH",id,"SUCCEEDED",null);
        }catch(Exception failure){String message=failure instanceof ApiException api?com.example.report.operations.SensitiveData.text(api.getMessage()):"导出生成失败，请检查任务状态后重试";
            jdbc.update("UPDATE query_export_job SET status='FAILED',content=NULL,claim_token=NULL,lease_until=NULL,message=?,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_until>UTC_TIMESTAMP(3)",message,id,token);}
    }
    /** 续租必须仍持有有效令牌且快照未到期；不递增状态版本，避免进度心跳制造重复提醒。 */
    private void renew(String id,String token,int progress){if(jdbc.update("UPDATE query_export_job SET lease_until=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3)),progress_rows=? WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_until>UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",progress,id,token)!=1)throw new ApiException(409,"导出已取消、过期或失去执行租约");}
    /** 到期文件先撤销后删除快照明细，保留稳定请求头30天；不触及来源业务或派单证据。 */
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void cleanup(){String tenant=permissions.tenantId();recover();jdbc.update("UPDATE query_export_job e LEFT JOIN business_query_run r ON r.id=e.run_id SET e.status='EXPIRED',e.content=NULL,e.content_hash=NULL,e.claim_token=NULL,e.row_version=e.row_version+1,e.message='查询快照已失效' WHERE e.tenant_id=? AND e.status<>'EXPIRED' AND (r.id IS NULL OR r.expires_at<=UTC_TIMESTAMP(3))",tenant);
        snapshots.cleanup(tenant);jdbc.update("DELETE FROM query_export_job WHERE tenant_id=? AND created_at<TIMESTAMPADD(DAY,-30,UTC_TIMESTAMP(3)) AND status NOT IN ('QUEUED','RUNNING') LIMIT 100",tenant);
        jdbc.update("DELETE FROM task_inbox_read WHERE tenant_id=? AND read_at<TIMESTAMPADD(DAY,-30,UTC_TIMESTAMP(3)) LIMIT 500",tenant);}
    private Map<String,Object> owned(CurrentUser user,String id){var rows=jdbc.queryForList("SELECT id,run_id,status,row_version,progress_rows,message,expires_at,created_at,content_hash FROM query_export_job WHERE id=? AND tenant_id=? AND user_id=?",id,user.tenantId(),user.userId());if(rows.isEmpty())throw ApiException.notFound("导出任务不存在");return rows.get(0);}
    private static Job view(Map<String,Object> row){return new Job(row.get("id").toString(),row.get("run_id").toString(),row.get("status").toString(),((Number)row.get("row_version")).longValue(),((Number)row.get("progress_rows")).intValue(),(String)row.get("message"),QuerySnapshotStore.instant(row.get("expires_at")).toString(),QuerySnapshotStore.instant(row.get("created_at")).toString(),(String)row.get("content_hash"));}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
