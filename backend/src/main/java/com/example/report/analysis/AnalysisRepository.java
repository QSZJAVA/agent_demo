package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.investigation.InvestigationJson;
import com.example.report.permission.CurrentUser;
import com.example.report.workspace.QuerySnapshotStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 本人只读分析任务的持久仓储；提交幂等、短事务准入、令牌租约和终态条件回写共同防止重复执行与迟到发布。 */
@Repository
public class AnalysisRepository {
    private final JdbcTemplate jdbc;private final QuerySnapshotStore snapshots;private final TransactionTemplate tx;
    public AnalysisRepository(JdbcTemplate jdbc,QuerySnapshotStore snapshots,PlatformTransactionManager manager){this.jdbc=jdbc;this.snapshots=snapshots;tx=new TransactionTemplate(manager);tx.setTimeout(10);}

    /** 同租户本人请求号只能对应同一完整输入；回放任何状态都不会再次排队或重新消耗模型。 */
    public Map<String,Object> submit(CurrentUser user,AnalysisContract.Request request,String key,String scope,String config) {
        if(key==null||!key.matches("[a-zA-Z0-9_-]{8,100}"))throw new ApiException(422,"分析请求号无效");String hash=InvestigationJson.hash(request);
        return tx.execute(status->{snapshots.lock(user.tenantId());
            var existing=jdbc.queryForList("SELECT * FROM business_analysis_run WHERE tenant_id=? AND user_id=? AND request_key=?",user.tenantId(),user.userId(),key);
            if(!existing.isEmpty()){var row=existing.get(0);if(!hash.equals(row.get("request_hash")))throw new ApiException(409,"该请求号已绑定其他分析内容");return require(user,row.get("id").toString());}
            var active=jdbc.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(user_id=?),0) owned FROM business_analysis_run WHERE tenant_id=? AND status IN ('QUEUED','RUNNING')",user.userId(),user.tenantId());
            if(((Number)active.get("total")).intValue()>=8||((Number)active.get("owned")).intValue()>=2)throw new ApiException(429,"分析任务并发已满，请等待或取消现有任务");
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM business_analysis_run WHERE tenant_id=? AND user_id=? AND expires_at>UTC_TIMESTAMP(3)",Integer.class,user.tenantId(),user.userId());
            if(count>=50)throw new ApiException(429,"本人分析任务保留量已满，请等待到期清理");String id=JsonUtil.newId();
            jdbc.update("INSERT INTO business_analysis_run(id,tenant_id,user_id,company_code,report_id,permission_version,scope_hash,request_key,request_hash,request_json,config_hash,steps_json,usage_json,status,stage,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,'[]','[]','QUEUED','QUEUED',TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(3)))",
                    id,user.tenantId(),user.userId(),request.companyCode(),request.reportId(),user.permissionVersion(),scope,key,hash,JsonUtil.toJson(request),config);return require(user,id);
        });
    }
    /** 身份、期限之外，服务层还须核对当前业务口径与知识摘要，仓储本身不授予永久访问权。 */
    public Map<String,Object> require(CurrentUser user,String id){var rows=jdbc.queryForList("SELECT * FROM business_analysis_run WHERE id=? AND tenant_id=? AND user_id=?",id,user.tenantId(),user.userId());
        if(rows.isEmpty()||!user.permissionVersion().equals(rows.get(0).get("permission_version")))throw ApiException.notFound("分析不存在或当前无权访问");var row=rows.get(0);
        if(!QuerySnapshotStore.instant(row.get("expires_at")).isAfter(java.time.Instant.now()))throw new ApiException(410,"分析证据已过期，请重新发起");return row;}
    /** 聊天发起的任务随会话删除立即失去读取和继续执行权；独立分析任务不因此增加会话依赖。 */
    public void requireConversation(String id) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM chat_turn_receipt r LEFT JOIN agent_conversation c ON c.id=r.conversation_id AND c.tenant_id=r.tenant_id AND c.user_id=r.user_id WHERE r.analysis_run_id=? AND (c.id IS NULL OR c.status<>'active' OR EXISTS(SELECT 1 FROM conversation_erasure e WHERE e.conversation_id=r.conversation_id)))",Boolean.class,id)))
            throw ApiException.notFound("分析所属会话已删除，任务不再可用");
    }
    /** 最近50项的有界本人任务入口；可见性由服务层逐项按当前范围过滤。 */
    public List<Map<String,Object>> recent(CurrentUser user){return jdbc.queryForList("SELECT * FROM business_analysis_run WHERE tenant_id=? AND user_id=? AND permission_version=? AND expires_at>UTC_TIMESTAMP(3) ORDER BY created_at DESC,id DESC LIMIT 50",user.tenantId(),user.userId(),user.permissionVersion());}

    /** 一次只认领部署租户的一个任务，模型调用从不占用该事务；SKIP LOCKED避免其他工作线程等待。 */
    public Map<String,Object> claim(String tenant){return tx.execute(status->{var rows=jdbc.queryForList("SELECT * FROM business_analysis_run WHERE tenant_id=? AND status='QUEUED' AND expires_at>UTC_TIMESTAMP(3) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",tenant);
        if(rows.isEmpty())return null;var row=rows.get(0);String token=JsonUtil.newId();jdbc.update("UPDATE business_analysis_run SET status='RUNNING',stage='PLAN',claim_token=?,lease_until=TIMESTAMPADD(SECOND,45,UTC_TIMESTAMP(3)),started_at=UTC_TIMESTAMP(3),row_version=row_version+1 WHERE id=? AND tenant_id=? AND status='QUEUED'",token,row.get("id"),tenant);row.put("claim_token",token);return row;});}
    /** 持有有效令牌才能续租；取消或失租不可通过心跳重新取得所有权。 */
    public boolean renew(String id,String token){return jdbc.update("UPDATE business_analysis_run SET lease_until=TIMESTAMPADD(SECOND,45,UTC_TIMESTAMP(3)) WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",id,token)==1;}
    public void guard(String id,String token){if(Thread.currentThread().isInterrupted()||jdbc.queryForObject("SELECT COUNT(*) FROM business_analysis_run WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",Integer.class,id,token)!=1)throw new ApiException(409,"分析已取消、中断或失去执行租约");}
    /** 每个已验证阶段单独落库；部分步骤保留真实完成事实，不能把失败步骤标为成功。 */
    public void progress(String id,String token,String stage,Object plan,Object steps,Object usage){String stepJson=JsonUtil.toJson(steps);if(stepJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>512*1024)throw new ApiException(422,"分析步骤证据超过512KiB预算");
        if(jdbc.update("UPDATE business_analysis_run SET stage=?,plan_json=?,steps_json=?,usage_json=?,row_version=row_version+1 WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",stage,plan==null?null:JsonUtil.toJson(plan),stepJson,JsonUtil.toJson(usage),id,token)!=1)throw new ApiException(409,"分析阶段已失去回写权");}
    /** 完成、失败和部分完成都必须持有当前令牌；迟到的模型响应不能复活取消或已中断任务。 */
    public boolean finish(String id,String token,String status,String reason,String message,Object answer,Object usage){if(!Set.of("COMPLETED","CLARIFY","PARTIAL","FAILED").contains(status))throw new IllegalArgumentException("分析终态无效");
        return jdbc.update("UPDATE business_analysis_run SET status=?,stage='FINISHED',reason=?,message=?,answer_json=?,usage_json=?,claim_token=NULL,lease_until=NULL,finished_at=UTC_TIMESTAMP(3),row_version=row_version+1 WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",status,reason,message,answer==null?null:JsonUtil.toJson(answer),JsonUtil.toJson(usage),id,token)==1;}
    /** 取消排队或执行中的本人任务；重复取消幂等，已完成任务保留结果。 */
    public void cancel(CurrentUser user,String id){require(user,id);jdbc.update("UPDATE business_analysis_run SET status='CANCELLED',stage='FINISHED',reason='USER_CANCELLED',message='用户已取消',claim_token=NULL,lease_until=NULL,finished_at=UTC_TIMESTAMP(3),row_version=row_version+1 WHERE id=? AND tenant_id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",id,user.tenantId(),user.userId());}
    /** 排队超时或执行失租只结束原任务；跨进程不自动重放模型，重新分析必须使用新的显式请求。 */
    public void recover(String tenant){jdbc.update("UPDATE business_analysis_run SET status='INTERRUPTED',stage='FINISHED',reason='LEASE_LOST',message='执行中断，请查看已完成步骤并明确重新分析',claim_token=NULL,lease_until=NULL,finished_at=UTC_TIMESTAMP(3),row_version=row_version+1 WHERE tenant_id=? AND ((status='RUNNING' AND lease_until<=UTC_TIMESTAMP(3)) OR (status='QUEUED' AND created_at<TIMESTAMPADD(SECOND,-120,UTC_TIMESTAMP(3))))",tenant);}
    public void cleanup(String tenant){jdbc.update("DELETE FROM business_analysis_run WHERE tenant_id=? AND expires_at<=UTC_TIMESTAMP(3) LIMIT 10",tenant);}
    static Object json(Map<String,Object> row,String field){Object value=row.get(field);if(value==null)return null;try{return JsonUtil.MAPPER.readValue(value.toString(),Object.class);}catch(Exception invalid){throw new ApiException(503,"分析持久化证据不符合当前协议");}}
}
