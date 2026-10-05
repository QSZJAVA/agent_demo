package com.example.report.operations;

import com.example.report.common.*;
import com.example.report.permission.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/** 数据留存和会话清理服务：先保存删除墓碑，再有界分批清理；在途、失败或待核对任务保留恢复证据与永久防重标识。 */
@Slf4j
@Service
public class DataRetentionService {
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final StringRedisTemplate redis;
    private final OperationsPolicy policies;
    private final OperationsAudit audit;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<com.example.report.investigation.InvestigationService> investigations;
    public DataRetentionService(JdbcTemplate jdbc,TransactionOperations tx,StringRedisTemplate redis,OperationsPolicy policies,OperationsAudit audit) {
        this.jdbc=jdbc;this.tx=tx;this.redis=redis;this.policies=policies;this.audit=audit;
    }
    /**
     * 先保存不可逆的删除墓碑并隐藏会话，再由定时任务分批清理；墓碑阻止在途消息、语义状态和工作记忆重新写回。
     */
    public void request(CurrentUser user,String id,String reason) {
        OperationsPolicy.requireReason(reason);
        tx.executeWithoutResult(status -> {
            var owner=jdbc.queryForList("SELECT user_id FROM agent_conversation WHERE tenant_id=? AND id=? FOR UPDATE",user.tenantId(),id);
            if(owner.isEmpty() || (!user.admin()&&!user.userId().equals(owner.get(0).get("user_id")))) throw ApiException.notFound("会话不存在");
            jdbc.update("INSERT IGNORE INTO conversation_erasure(conversation_id,tenant_id,requested_by,requested_at) VALUES (?,?,?,NOW(3))",id,user.tenantId(),user.userId());
            jdbc.update("UPDATE agent_conversation SET status='deleted',title=NULL WHERE tenant_id=? AND id=?",user.tenantId(),id);
            audit.record(user,"ERASURE_REQUEST",id,"PENDING",reason);
        });
    }
    public List<Map<String,Object>> pending(CurrentUser admin) {
        return jdbc.queryForList("SELECT conversation_id,requested_by,requested_at,status,completed_at FROM conversation_erasure WHERE tenant_id=? ORDER BY requested_at DESC LIMIT 100",admin.tenantId());
    }
    public boolean erased(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM conversation_erasure WHERE conversation_id=?)",Boolean.class,id));
    }
    public void requireWritable(String id) {
        if(erased(id)) throw new ApiException(409,"会话已进入删除流程");
    }
    /**
     * 按各租户保留策略有界清理；保留未完成执行和待核对证据。派单任务只清结果载荷，保留状态与幂等键避免旧请求重新写入。
     */
    @Scheduled(fixedDelayString="${agent.retention-sweep-ms:60000}")
    public void sweep() {
        try {
            var tenants=jdbc.queryForList("SELECT DISTINCT tenant_id FROM agent_conversation UNION SELECT DISTINCT tenant_id FROM operations_policy "
                    + "UNION SELECT DISTINCT tenant_id FROM dispatch_preview UNION SELECT DISTINCT tenant_id FROM business_metric "
                    + "UNION SELECT DISTINCT tenant_id FROM operations_audit",String.class);
            for(String tenant:tenants) {
                var p=policies.get(tenant,"retention").payload();
                int days=((Number)p.get("conversationDays")).intValue();
                var ids=jdbc.queryForList("SELECT id FROM agent_conversation WHERE tenant_id=? AND updated_at<TIMESTAMPADD(DAY,?,NOW()) AND NOT EXISTS(SELECT 1 FROM conversation_erasure e WHERE e.conversation_id=agent_conversation.id) ORDER BY updated_at LIMIT 20",String.class,tenant,-days);
                var system=new CurrentUser(tenant,"retention-service","数据留存服务",Set.of(),Set.of(),true);
                for(String id:ids) request(system,id,"会话留存到期");
                purgeResults(tenant,((Number)p.get("resultDays")).intValue());
                jdbc.update("UPDATE dispatch_job SET result_json=NULL,message='任务结果保留期已到期' WHERE tenant_id=? AND status IN ('SUCCEEDED','FAILED') AND result_json IS NOT NULL AND updated_at<TIMESTAMPADD(DAY,?,NOW()) LIMIT 1000",tenant,-((Number)p.get("resultDays")).intValue());
                jdbc.update("DELETE FROM business_metric WHERE tenant_id=? AND created_at<TIMESTAMPADD(DAY,?,NOW()) LIMIT 1000",tenant,-((Number)p.get("metricDays")).intValue());
                jdbc.update("DELETE FROM operations_audit WHERE tenant_id=? AND created_at<TIMESTAMPADD(DAY,?,NOW()) LIMIT 1000",tenant,-((Number)p.get("auditDays")).intValue());
            }
            var pending=jdbc.queryForList("SELECT conversation_id FROM conversation_erasure WHERE status='PENDING' ORDER BY requested_at LIMIT 20",String.class);
            for(String id:pending) erase(id);
        } catch(RuntimeException failure) { log.warn("留存清理未完成，保留请求下轮重试",failure); }
    }
    public void erase(String id) {
        // A late memory writer checks the tombstone both before and after SET.
        redis.delete("agent:memory:"+id);
        tx.executeWithoutResult(status -> {
            var rows=jdbc.queryForList("SELECT tenant_id FROM agent_conversation WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty() || !erased(id)) return;
            String tenant=rows.get(0).get("tenant_id").toString();
            if(held(id)) return;
            // 会话墓碑已经生效；调查报告和证据不能在删除后形成孤立的可读记录。
            var investigation=investigations==null?null:investigations.getIfAvailable();
            if(investigation!=null) investigation.eraseConversation(tenant,id);
            jdbc.update("DELETE FROM semantic_dialogue WHERE tenant_id=? AND conversation_id=?",tenant,id);
            jdbc.update("DELETE FROM semantic_turn WHERE tenant_id=? AND conversation_id=? LIMIT 500",tenant,id);
            // Delete bounded batches; tombstone prevents replay from adding new messages.
            jdbc.update("DELETE FROM agent_message WHERE tenant_id=? AND conversation_id=? LIMIT 500",tenant,id);
            jdbc.update("DELETE FROM trace_event WHERE tenant_id=? AND conversation_id=? AND event_type='MESSAGE' LIMIT 500",tenant,id);
            long left=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM agent_message WHERE tenant_id=? AND conversation_id=?)+(SELECT COUNT(*) FROM trace_event WHERE tenant_id=? AND conversation_id=? AND event_type='MESSAGE')",Long.class,tenant,id,tenant,id);
            long semanticLeft=jdbc.queryForObject("SELECT COUNT(*) FROM semantic_turn WHERE tenant_id=? AND conversation_id=?",Long.class,tenant,id);
            if(left==0 && semanticLeft==0) {
                jdbc.update("UPDATE agent_conversation SET title=NULL,message_count=0,status='deleted' WHERE id=?",id);
                jdbc.update("UPDATE conversation_erasure SET status='COMPLETED',completed_at=NOW(3) WHERE conversation_id=? AND status='PENDING'",id);
            }
        });
    }
    /**
     * 在途预览、未完成派单、失败或未知条目仍需证据支持恢复；命中任一条件时延后会话清理。
     */
    private boolean held(String conversation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM dispatch_plan WHERE conversation_id=? AND
                  (status IN ('EXECUTING','REVIEW_REQUIRED') OR (status='PENDING' AND expires_at>NOW()) OR EXISTS(SELECT 1 FROM dispatch_plan_item i WHERE i.plan_id=dispatch_plan.id AND i.status IN ('FAILED','UNKNOWN'))))
                  OR EXISTS(SELECT 1 FROM dispatch_preview_job WHERE conversation_id=? AND status IN ('QUEUED','RUNNING'))
                """,Boolean.class,conversation,conversation));
    }
    /**
     * 结果到期后清理展示事实，保留清单条目终态和请求号以继续去重。与创建和认领共用预览锁，避免扫描后新任务出现而误删证据。
     */
    private void purgeResults(String tenant,int days) {
        var ids=jdbc.queryForList("""
                SELECT v.id FROM dispatch_preview v WHERE v.tenant_id=? AND v.expires_at<TIMESTAMPADD(DAY,?,NOW())
                  AND v.status<>'BUILDING' AND COALESCE(v.status_reason,'')<>'DATA_PURGED'
                  AND NOT EXISTS(SELECT 1 FROM dispatch_plan p WHERE p.preview_id=v.id
                    AND (p.status IN ('EXECUTING','REVIEW_REQUIRED') OR EXISTS(SELECT 1 FROM dispatch_plan_item i WHERE i.plan_id=p.id AND i.status IN ('FAILED','UNKNOWN'))))
                  AND NOT EXISTS(SELECT 1 FROM trace_event e WHERE e.preview_id=v.id AND e.delivery_status='PENDING')
                ORDER BY v.expires_at LIMIT 20
                """,String.class,tenant,-days);
        for(String id:ids) tx.executeWithoutResult(status -> {
            // Use the same preview lock as plan creation and claiming.
            var locked=jdbc.queryForList("SELECT id FROM dispatch_preview WHERE tenant_id=? AND id=? FOR UPDATE",tenant,id);
            if(locked.isEmpty()) return;
            long active=jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan WHERE preview_id=? AND (status IN ('EXECUTING','REVIEW_REQUIRED') OR EXISTS(SELECT 1 FROM dispatch_plan_item i WHERE i.plan_id=dispatch_plan.id AND i.status IN ('FAILED','UNKNOWN')))",Long.class,id);
            if(active>0) return;
            jdbc.update("UPDATE dispatch_preview SET status='EXPIRED' WHERE id=?",id);
            jdbc.update("UPDATE dispatch_plan SET status='EXPIRED',status_reason='DATA_PURGED' WHERE preview_id=? AND status='PENDING'",id);
            jdbc.update("DELETE FROM dispatch_preview_item WHERE preview_id=? LIMIT 500",id);
            // Keep request IDs and terminal item states for permanent deduplication; clear display data.
            jdbc.update("UPDATE dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id SET i.doc_no=NULL,i.label=NULL,i.amount=NULL,i.biz_date=NULL,i.error_message=NULL WHERE p.preview_id=?",id);
            jdbc.update("DELETE FROM dispatch_audit WHERE preview_id=? LIMIT 500",id);
            jdbc.update("DELETE FROM trace_event WHERE preview_id=? AND delivery_status='DELIVERED' LIMIT 500",id);
            jdbc.update("DELETE FROM agent_message WHERE preview_id=? LIMIT 500",id);
            long left=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM dispatch_preview_item WHERE preview_id=?)+(SELECT COUNT(*) FROM trace_event WHERE preview_id=?)+(SELECT COUNT(*) FROM agent_message WHERE preview_id=?)+(SELECT COUNT(*) FROM dispatch_audit WHERE preview_id=?)",Long.class,id,id,id,id);
            if(left==0) jdbc.update("UPDATE dispatch_preview SET status_reason='DATA_PURGED',query_json='{}',summary_json='{}' WHERE id=?",id);
        });
    }
}
