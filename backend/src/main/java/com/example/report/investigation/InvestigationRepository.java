package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.permission.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.function.Supplier;

/** 调查运行、步骤和证据的事务仓储；短事务认领和条件回写，取消或失租后迟到结果无权持久化。 */
@Repository
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationRepository {
    /**
     * 准入事务结果；并发同键的赢家与回放必须使用不同HTTP接收语义。
     * @param run 新建或已有的当前任务行
     * @param replayed 是否在准入锁内命中同键同负载
     */
    public record Creation(Map<String,Object> run,boolean replayed) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final InvestigationProperties props;
    public InvestigationRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, InvestigationProperties props) {
        this.jdbc=jdbc; this.props=props;tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    public Map<String,Object> find(String id) {
        var rows=jdbc.queryForList("SELECT * FROM agent_investigation_run WHERE id=?",id);
        if(rows.isEmpty()) throw ApiException.notFound("调查任务不存在");return rows.get(0);
    }
    public Optional<Map<String,Object>> byKey(CurrentUser actor, String key) {
        return jdbc.queryForList("SELECT * FROM agent_investigation_run WHERE tenant_id=? AND actor_id=? AND idempotency_key=?",actor.tenantId(),actor.userId(),key).stream().findFirst();
    }
    /** 租户互斥行串行检查在途容量；同键负载校验和同清单唯一性必须在准入事务内成立。 */
    public Creation create(CurrentUser actor, Map<String,Object> request, String key, String hash, Map<String,Object> config, Supplier<Long> version) {
        return tx.execute(s -> {
            jdbc.update("INSERT INTO agent_investigation_queue(tenant_id) VALUES (?) ON DUPLICATE KEY UPDATE tenant_id=tenant_id",actor.tenantId());
            jdbc.queryForObject("SELECT tenant_id FROM agent_investigation_queue WHERE tenant_id=? FOR UPDATE",String.class,actor.tenantId());
            // 在互斥锁之后才建立RR读取视图，避免看不到刚提交的同键赢家而触发唯一键错误。
            var prior=byKey(actor,key);if(prior.isPresent()) return new Creation(replay(prior.get(),hash),true);
            String active=Digests.sha256(JsonUtil.toJson(List.of(actor.tenantId(),actor.userId(),request.get("planId"))));
            var existing=jdbc.queryForList("SELECT id FROM agent_investigation_run WHERE active_key=?",String.class,active);
            if(!existing.isEmpty()) throw new ApiException(409,"该清单已有在途调查："+existing.get(0));
            long tenant=jdbc.queryForObject("SELECT COUNT(*) FROM agent_investigation_run WHERE tenant_id=? AND status IN ('QUEUED','RUNNING')",Long.class,actor.tenantId());
            long user=jdbc.queryForObject("SELECT COUNT(*) FROM agent_investigation_run WHERE tenant_id=? AND actor_id=? AND status IN ('QUEUED','RUNNING')",Long.class,actor.tenantId(),actor.userId());
            if(tenant>=props.getMaxActivePerTenant() || user>=props.getMaxActivePerUser()) throw new ApiException(429,"调查任务队列已满，请等待已有任务结束");
            long expected=version.get();String id=JsonUtil.newId();
            jdbc.update("INSERT INTO agent_investigation_run(id,tenant_id,actor_id,plan_id,plan_owner_id,idempotency_key,payload_hash,request_json,scope_hash,active_key,expected_execution_version,model_name,config_json,created_at,updated_at,queue_expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)))",
                    id,actor.tenantId(),actor.userId(),request.get("planId"),request.get("ownerId"),key,hash,JsonUtil.toJson(request),Digests.sha256(JsonUtil.toJson(List.of(request.get("planId"),request.get("selectedItemIds")))),active,expected,config.get("model"),JsonUtil.toJson(config),props.getQueueTimeoutSeconds());
            return new Creation(find(id),false);
        });
    }
    public Map<String,Object> replay(Map<String,Object> prior, String hash) {
        if(!hash.equals(prior.get("payload_hash"))) throw new ApiException(409,"调查幂等键对应不同负载");return prior;
    }
    /** 按部署租户使用SKIP LOCKED，使多实例只认领本租户的一份任务；不在内存堆积待执行队列。 */
    public Map<String,Object> claim(String tenantId) {
        return tx.execute(s -> {
            var rows=jdbc.queryForList("SELECT id FROM agent_investigation_run WHERE tenant_id=? AND status='QUEUED' AND queue_expires_at>UTC_TIMESTAMP(3) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",tenantId);
            if(rows.isEmpty()) return null;String id=rows.get(0).get("id").toString();
            jdbc.update("UPDATE agent_investigation_run SET status='RUNNING',claim_token=?,started_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),deadline_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),row_version=row_version+1 WHERE id=? AND status='QUEUED'",JsonUtil.newId(),props.getLeaseSeconds(),props.getRunTimeoutSeconds(),id);
            return find(id);
        });
    }
    /** 严格续租：已过期令牌不能复活；返回false后工作线程必须停止。 */
    public boolean renew(String id,String token) {
        return jdbc.update("UPDATE agent_investigation_run SET lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)) WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3) AND deadline_at>UTC_TIMESTAMP(3)",props.getLeaseSeconds(),id,token)==1;
    }
    public void guard(String id,String token) {
        if(Thread.currentThread().isInterrupted() || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_investigation_run WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3) AND deadline_at>UTC_TIMESTAMP(3))",Boolean.class,id,token)))
            throw new InvestigationFailure("LEASE_LOST","调查已取消、超时或执行权失效");
    }
    /** 带认领令牌的短事务；锁内再次校验，不允许取消和结果提交交错后回写。 */
    public <T> T fenced(String id,String token,Supplier<T> action) {
        return tx.execute(s -> {jdbc.queryForObject("SELECT id FROM agent_investigation_run WHERE id=? FOR UPDATE",String.class,id);guard(id,token);return action.get();});
    }
    public void snapshot(String id,String token,Map<String,Object> snapshot) {
        fenced(id,token,() -> {jdbc.update("UPDATE agent_investigation_run SET snapshot_json=?,source_fingerprint=?,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",JsonUtil.toJson(snapshot),snapshot.get("fingerprint"),id);return null;});
    }
    /** 调用之前保存STARTED和计数；失败、参数错误与缓存复用都不能绕过预算统计。 */
    public int startStep(String id,String token,String kind,String toolId,String name,Object arguments) {
        return fenced(id,token,() -> {
            int seq=jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM agent_investigation_step WHERE run_id=?",Integer.class,id);
            jdbc.update("INSERT INTO agent_investigation_step(run_id,tenant_id,seq,kind,status,tool_call_id,tool_name,arguments_json,started_at) SELECT id,tenant_id,?,?,'STARTED',?,?,?,UTC_TIMESTAMP(3) FROM agent_investigation_run WHERE id=?",seq,kind,toolId,name,arguments==null?null:JsonUtil.toJson(arguments),id);
            if("MODEL".equals(kind)) jdbc.update("UPDATE agent_investigation_run SET model_calls=model_calls+1 WHERE id=?",id);
            if("TOOL".equals(kind)) jdbc.update("UPDATE agent_investigation_run SET tool_calls=tool_calls+1 WHERE id=?",id);
            return seq;
        });
    }
    public void endStep(String id,String token,int seq,String status,Object result,String error,long duration,Object usage) {
        fenced(id,token,() -> {jdbc.update("UPDATE agent_investigation_step SET status=?,result_json=?,error_code=?,duration_ms=?,usage_json=?,finished_at=UTC_TIMESTAMP(3) WHERE run_id=? AND seq=? AND status='STARTED'",status,result==null?null:JsonUtil.toJson(result),error,duration,usage==null?null:JsonUtil.toJson(usage),id,seq);return null;});
    }
    public void countMcp(String id,String token) {fenced(id,token,() -> {jdbc.update("UPDATE agent_investigation_run SET mcp_calls=mcp_calls+1 WHERE id=?",id);return null;});}
    /** 证据只在有效认领期间生成，不允许旧工作线程把迟到HTTP结果变成新报告依据。 */
    public String evidence(String id,String token,String type,List<String> refs,Object source,Object content,boolean truncated) {
        return fenced(id,token,() -> {
            int n=jdbc.queryForObject("SELECT COUNT(*)+1 FROM agent_investigation_evidence WHERE run_id=?",Integer.class,id);
            int bytes=jdbc.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(content_json)),0) FROM agent_investigation_evidence WHERE run_id=?",Integer.class,id);
            String json=JsonUtil.toJson(content);if(bytes+json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>512*1024) throw new InvestigationFailure("BUDGET_EXHAUSTED","调查证据体积已达到上限");
            String ref="E"+n;
            jdbc.update("INSERT INTO agent_investigation_evidence(run_id,tenant_id,evidence_ref,source_type,item_refs_json,source_ref_json,content_json,content_hash,observed_at,truncated,created_at) SELECT id,tenant_id,?,?,?,?,?,?,UTC_TIMESTAMP(3),?,UTC_TIMESTAMP(3) FROM agent_investigation_run WHERE id=?",ref,type,JsonUtil.toJson(refs),JsonUtil.toJson(source),json,Digests.sha256(json),truncated,id);
            return ref;
        });
    }
    public Map<String,Object> evidence(String id,String ref) {
        var rows=jdbc.queryForList("SELECT evidence_ref,source_type,item_refs_json,content_json,observed_at,truncated FROM agent_investigation_evidence WHERE run_id=? AND evidence_ref=?",id,ref);
        if(rows.isEmpty()) throw ApiException.notFound("证据不存在");return rows.get(0);
    }
    /** 最终状态与未完成步骤原子收尾；外部调用从不处于此事务中。 */
    public void finish(String id,String token,String status,String reason,String message,Object report,Object usage,Runnable validate) {
        tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM agent_investigation_run WHERE id=? FOR UPDATE",String.class,id);
            // 截止时间只禁止新调用；持有未失效认领令牌的线程仍须能把超时明确收尾。
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_investigation_run WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3))",Boolean.class,id,token))) throw new InvestigationFailure("LEASE_LOST","调查执行权已失效");
            validate.run();
            jdbc.update("UPDATE agent_investigation_run SET status=?,stop_reason=?,message=?,report_json=?,usage_json=?,active_key=NULL,claim_token=NULL,lease_until=NULL,finished_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),row_version=row_version+1 WHERE id=?",status,reason,message,report==null?null:JsonUtil.toJson(report),JsonUtil.toJson(usage),id);
            closeSteps(id,reason);
        });
    }
    /** 取消幂等；终态保持原状态，正在执行的迟到响应无权继续追加步骤。 */
    public Map<String,Object> cancel(String id) {
        return tx.execute(s -> {
            jdbc.queryForObject("SELECT id FROM agent_investigation_run WHERE id=? FOR UPDATE",String.class,id);
            jdbc.update("UPDATE agent_investigation_run SET status='CANCELLED',stop_reason='USER_CANCELLED',message='调查已取消',active_key=NULL,claim_token=NULL,lease_until=NULL,finished_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),row_version=row_version+1 WHERE id=? AND status IN ('QUEUED','RUNNING')",id);
            closeSteps(id,"USER_CANCELLED");return find(id);
        });
    }
    private void closeSteps(String id,String reason) {
        jdbc.update("UPDATE agent_investigation_step SET status='FAILED',error_code=?,finished_at=UTC_TIMESTAMP(3),duration_ms=COALESCE(duration_ms,0) WHERE run_id=? AND status='STARTED'",reason,id);
    }
    /** 排队过期与执行失租只收尾，不自动重放模型；小批量避免锁住整个任务表。 */
    public void recover(String tenantId) {
        var ids=jdbc.queryForList("SELECT id FROM agent_investigation_run WHERE tenant_id=? AND ((status='QUEUED' AND queue_expires_at<=UTC_TIMESTAMP(3)) OR (status='RUNNING' AND (lease_until<=UTC_TIMESTAMP(3) OR deadline_at<=UTC_TIMESTAMP(3)))) LIMIT 50",String.class,tenantId);
        for(String id:ids) tx.executeWithoutResult(s -> {
            var run=jdbc.queryForMap("SELECT * FROM agent_investigation_run WHERE id=? FOR UPDATE",id);
            boolean queued="QUEUED".equals(run.get("status"));
            boolean expired=!queued && Boolean.TRUE.equals(jdbc.queryForObject("SELECT deadline_at<=UTC_TIMESTAMP(3) FROM agent_investigation_run WHERE id=?",Boolean.class,id));
            String reason=queued?"QUEUE_TIMEOUT":expired?"BUDGET_EXHAUSTED":"LEASE_LOST";
            int changed=jdbc.update("UPDATE agent_investigation_run SET status=?,stop_reason=?,message='调查已超时或中断，请读取已有步骤后重新分析',claim_token=NULL,active_key=NULL,lease_until=NULL,finished_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),row_version=row_version+1 WHERE id=? AND ((status='QUEUED' AND queue_expires_at<=UTC_TIMESTAMP(3)) OR (status='RUNNING' AND (lease_until<=UTC_TIMESTAMP(3) OR deadline_at<=UTC_TIMESTAMP(3))))",queued || expired?"FAILED":"INTERRUPTED",reason,id);
            if(changed>0) closeSteps(id,reason);
        });
    }
    public List<Map<String,Object>> steps(String id,long after,int size) {
        return jdbc.queryForList("SELECT seq,kind,status,tool_name,arguments_json,result_json,error_code,duration_ms,started_at,finished_at FROM agent_investigation_step WHERE run_id=? AND seq>? AND status<>'STARTED' AND seq<COALESCE((SELECT MIN(seq) FROM agent_investigation_step WHERE run_id=? AND status='STARTED'),2147483647) ORDER BY seq LIMIT ?",id,after,id,size+1);
    }
    public Object activeStep(String id) {return jdbc.queryForList("SELECT seq,kind,tool_name,started_at FROM agent_investigation_step WHERE run_id=? AND status='STARTED' ORDER BY seq LIMIT 1",id).stream().findFirst().orElse(null);}
    public List<Map<String,Object>> list(CurrentUser actor,String plan,String cursor,int size) {
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.userId(),plan));String seek="";
        if(!cursor.isEmpty()) {
            var anchors=jdbc.queryForList("SELECT created_at,id FROM agent_investigation_run WHERE tenant_id=? AND actor_id=? AND plan_id=? AND id=?",actor.tenantId(),actor.userId(),plan,cursor);
            if(anchors.isEmpty()) throw new ApiException("任务游标已失效，请重新读取第一页");
            seek=" AND (created_at<? OR (created_at=? AND id<?))";var anchor=anchors.get(0);args.add(anchor.get("created_at"));args.add(anchor.get("created_at"));args.add(cursor);
        }
        args.add(size+1);
        return jdbc.queryForList("SELECT id,status,stop_reason,created_at FROM agent_investigation_run WHERE tenant_id=? AND actor_id=? AND plan_id=?"+seek+" ORDER BY created_at DESC,id DESC LIMIT ?",args.toArray());
    }
    /** 保留期内保留幂等身份；到期只清理终态，外键要求子记录先删除。 */
    public void clean(String tenantId) {
        var ids=jdbc.queryForList("SELECT id FROM agent_investigation_run WHERE tenant_id=? AND status NOT IN ('QUEUED','RUNNING') AND finished_at<TIMESTAMPADD(DAY,?,UTC_TIMESTAMP(3)) LIMIT 20",String.class,tenantId,-props.getRetentionDays());
        for(String id:ids) delete(id);
    }
    public void delete(String id) {
        tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM agent_investigation_run WHERE id=? FOR UPDATE",String.class,id);
            jdbc.update("DELETE FROM agent_investigation_step WHERE run_id=?",id);jdbc.update("DELETE FROM agent_investigation_evidence WHERE run_id=?",id);jdbc.update("DELETE FROM agent_investigation_run WHERE id=?",id);
        });
    }
    public static Map<String,Object> json(Map<String,Object> row,String field) {return row.get(field)==null?Map.of():JsonUtil.toMap(row.get(field).toString());}
}
