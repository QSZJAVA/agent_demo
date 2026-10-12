package com.example.report.semantic;

import com.example.report.analysis.AnalysisService;
import com.example.report.assistant.AssistantInteraction;
import com.example.report.common.*;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.RecordKey;
import com.example.report.investigation.InvestigationJson;
import com.example.report.permission.CurrentUser;
import com.example.report.workspace.QuerySnapshotStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;
import java.time.Instant;
import java.util.*;

/**
 * 聊天模型调用前的可靠接收仓储；完整输入哈希、本人幂等键和会话墓碑共同防止丢响应后重复建会话或执行。
 * 可信分析请求与子任务关联只在持有会话租约的短事务内写入；读取回执不调用模型、不重新解释问题。
 */
@Repository
public class ChatTurnReceiptStore {
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final ConversationService conversations;
    private final DialogueStore dialogues;

    public ChatTurnReceiptStore(JdbcTemplate jdbc,TransactionOperations tx,ConversationService conversations,DialogueStore dialogues) {
        this.jdbc=jdbc;this.tx=tx;this.conversations=conversations;this.dialogues=dialogues;
    }

    /**
     * 完整聊天输入；首次提交的空会话标识也参与哈希，核对时不得替换为响应中生成的会话标识。
     * @param conversationId 原始会话标识，首次提交为空
     * @param message 本轮完整原文，1至2000字符，不以模型改写替代
     * @param previewId 本轮勾选项所属预览，可空
     * @param excludedRecords 原始复合排除集合，可空；顺序与空值均保留用于核对
     * @param queryEdit 绑定原查询引用的完整编辑，可空
     * @param clarificationReply 绑定原澄清引用的选项，可空且与编辑互斥
     */
    public record Input(String conversationId,String message,String previewId,List<RecordKey> excludedRecords,
                        AssistantInteraction.QueryEdit queryEdit,AssistantInteraction.ClarificationReply clarificationReply) {
        public Input {
            if(message==null||message.isBlank()||message.length()>2000)throw new ApiException(422,"消息须为1至2000字");
            if(queryEdit!=null&&clarificationReply!=null||(queryEdit!=null||clarificationReply!=null)&&(conversationId==null||conversationId.isBlank()))
                throw new ApiException(422,"结构化回复须绑定原会话且一次只使用一种操作");
            if(conversationId!=null&&conversationId.length()>64||previewId!=null&&previewId.length()>64||excludedRecords!=null&&excludedRecords.size()>5000)
                throw new ApiException(422,"会话、预览或记录选择超出输入预算");
            if(excludedRecords!=null&&excludedRecords.stream().anyMatch(Objects::isNull))throw new ApiException(422,"排除集合不能包含空记录标识");
            excludedRecords=excludedRecords==null?null:List.copyOf(excludedRecords);
        }
    }

    /**
     * 接收或回放的原始记录，仅供服务端编排；接口必须投影允许展示的回执字段。
     * @param row 按数据库字段命名的本人记录，包含不可出站的完整性摘要
     * @param replayed 是否已存在同本人、同键、同完整载荷记录
     */
    public record Accepted(Map<String,Object> row,boolean replayed) { }

    /**
     * 新会话与接收记录原子提交；并发同键冲突在事务回滚后读取胜出记录，失败方不能留下第二个空会话。
     * @return 首次创建的记录，或同载荷回放；异载荷、到期或已删除记录永远不能重建
     */
    public Accepted receive(CurrentUser user,String model,String key,Input input) {
        validKey(key);String hash=InvestigationJson.hash(input);
        try {
            return tx.execute(status->{
                var old=find(user,key);if(!old.isEmpty())return replay(user,old.get(0),hash);
                var conversation=input.conversationId()==null||input.conversationId().isBlank()
                        ?conversations.create(user,model):conversations.getOwned(user,input.conversationId());
                owner(user,conversation.getId(),true);
                String id=JsonUtil.newId();
                jdbc.update("INSERT INTO chat_turn_receipt(request_id,tenant_id,user_id,client_key,conversation_id,request_hash,permission_version,status,created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?,'RECEIVED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(3)))",
                        id,user.tenantId(),user.userId(),key,conversation.getId(),hash,user.permissionVersion());
                return new Accepted(requireRequest(user,id),false);
            });
        } catch(DuplicateKeyException conflict) {
            // 必须在失败事务之外重读，避免重复读隔离级别的旧视图看不到胜出请求。
            var rows=find(user,key);if(rows.isEmpty())throw conflict;return replay(user,rows.get(0),hash);
        }
    }
    private Accepted replay(CurrentUser user,Map<String,Object> row,String hash) {
        if(!hash.equals(row.get("request_hash")))throw new ApiException(409,"该聊天请求号已绑定其他内容，请保留原请求进行核对");
        requireReadable(user,row);return new Accepted(row,true);
    }
    private List<Map<String,Object>> find(CurrentUser user,String key) {
        return jdbc.queryForList("SELECT * FROM chat_turn_receipt WHERE tenant_id=? AND user_id=? AND client_key=?",user.tenantId(),user.userId(),key);
    }
    /** 本人按客户端键查询原回执；无记录返回404，不能因此触发创建或模型重试。 */
    public Map<String,Object> require(CurrentUser user,String key) {
        validKey(key);var rows=find(user,key);if(rows.isEmpty())throw ApiException.notFound("本次提交尚无可读取的接收记录");
        var row=rows.get(0);requireReadable(user,row);return row;
    }
    /** 按服务端轮次核对归属；历史卡片必须先证明与该记录及原会话一致，再读取子分析。 */
    public Map<String,Object> requireRequest(CurrentUser user,String requestId) {
        var rows=jdbc.queryForList("SELECT * FROM chat_turn_receipt WHERE tenant_id=? AND user_id=? AND request_id=?",user.tenantId(),user.userId(),requestId);
        if(rows.isEmpty())throw ApiException.notFound("聊天轮次不存在或不可读取");var row=rows.get(0);requireReadable(user,row);return row;
    }
    /**
     * 处理完成不等于异步展示投影已完成；只有该本人会话没有待投影消息，客户端才可用历史替换收到的流内容。
     * 此读取不主动补写事件、不建立新轮次；失败投影仍保留可靠事件和原请求号等待恢复。
     */
    public boolean historyReady(CurrentUser user,String conversationId) {
        owner(user,conversationId,false);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM trace_event WHERE tenant_id=? AND user_id=? AND conversation_id=? AND event_type='MESSAGE' AND delivery_status='PENDING')",Boolean.class,user.tenantId(),user.userId(),conversationId));
    }
    private void requireReadable(CurrentUser user,Map<String,Object> row) {
        owner(user,row.get("conversation_id").toString(),false);
        if(!user.permissionVersion().equals(row.get("permission_version")))throw ApiException.notFound("本次提交的权限已变化，请重新登录并发送完整问题");
        if(!QuerySnapshotStore.instant(row.get("expires_at")).isAfter(Instant.now()))throw new ApiException(410,"本次提交的核对期限已到期，请发送新的完整问题");
    }
    private void owner(CurrentUser user,String id,boolean lock) {
        var rows=jdbc.queryForList("SELECT id FROM agent_conversation WHERE id=? AND tenant_id=? AND user_id=? AND status='active' AND NOT EXISTS(SELECT 1 FROM conversation_erasure e WHERE e.conversation_id=agent_conversation.id)"+(lock?" FOR UPDATE":""),id,user.tenantId(),user.userId());
        if(rows.isEmpty())throw ApiException.notFound("会话不存在或已删除");
    }
    /** 在已经取得的会话租约内CAS认领；只有尚未调用模型的RECEIVED可开始，超时PLANNING不能重推模型。 */
    public boolean begin(CurrentUser user,String requestId) {
        requireRequest(user,requestId);
        return jdbc.update("UPDATE chat_turn_receipt SET status='PLANNING',processing_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3) WHERE request_id=? AND tenant_id=? AND user_id=? AND permission_version=? AND status='RECEIVED' AND expires_at>UTC_TIMESTAMP(3)",
                dialogues.timeoutSeconds(),requestId,user.tenantId(),user.userId(),user.permissionVersion())==1;
    }
    /** 复核结束后冻结可信请求，独立提交READY检查点；连接中断不能把它覆盖为失败或丢失恢复依据。 */
    public void ready(CurrentUser user,String requestId,AnalysisService.Prepared prepared) {
        requireRequest(user,requestId);
        if(jdbc.update("UPDATE chat_turn_receipt SET status='READY',trusted_request_json=?,scope_hash=?,config_hash=?,processing_until=NULL,message=NULL,updated_at=UTC_TIMESTAMP(3) WHERE request_id=? AND tenant_id=? AND user_id=? AND permission_version=? AND status='PLANNING' AND processing_until>UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",
                JsonUtil.toJson(prepared.request()),prepared.scopeHash(),prepared.configHash(),requestId,user.tenantId(),user.userId(),user.permissionVersion())!=1)
            throw new ApiException(409,"本次聊天已失去分析提交权，请核对原提交状态");
    }
    /** 与子任务创建、状态和稳定卡片共用同一短事务，避免创建后断开只留下无法找到的任务。 */
    public void submitted(CurrentUser user,String requestId,String runId) {
        requireRequest(user,requestId);
        if(jdbc.update("UPDATE chat_turn_receipt SET status='SUBMITTED',analysis_run_id=?,message=NULL,updated_at=UTC_TIMESTAMP(3) WHERE request_id=? AND tenant_id=? AND user_id=? AND permission_version=? AND status='READY' AND expires_at>UTC_TIMESTAMP(3)",
                runId,requestId,user.tenantId(),user.userId(),user.permissionVersion())!=1)throw new ApiException(409,"本次聊天的分析关联状态已变化");
    }
    /** 明确核对已证明原依据失效时，在仍持有会话租约的独立短事务结束READY；瞬时服务和竞争错误不得调用。 */
    public void invalidateReady(CurrentUser user,String requestId,String message) {
        requireRequest(user,requestId);String safe=com.example.report.operations.SensitiveData.text(message);
        if(safe!=null&&safe.length()>512)safe=safe.substring(0,512);
        jdbc.update("UPDATE chat_turn_receipt SET status='FAILED',message=?,processing_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE request_id=? AND tenant_id=? AND user_id=? AND permission_version=? AND status='READY' AND expires_at>UTC_TIMESTAMP(3)",
                safe,requestId,user.tenantId(),user.userId(),user.permissionVersion());
    }
    /** 普通查询、帮助、派单准备或澄清处理完成；终态回放只恢复会话，不重复任何模型和业务操作。 */
    public void complete(CurrentUser user,String requestId) {
        jdbc.update("UPDATE chat_turn_receipt SET status='COMPLETED',processing_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE request_id=? AND tenant_id=? AND user_id=? AND permission_version=? AND status='PLANNING' AND processing_until>UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",
                requestId,user.tenantId(),user.userId(),user.permissionVersion());
    }
    /** 仅结束尚无可信分析依据的处理中轮次；READY/SUBMITTED由核对恢复，迟到异常不能覆盖已持久结果。 */
    public void failed(CurrentUser user,String requestId,boolean interrupted,String message) {
        String safe=com.example.report.operations.SensitiveData.text(message);if(safe!=null&&safe.length()>512)safe=safe.substring(0,512);
        jdbc.update("UPDATE chat_turn_receipt r SET status=?,message=?,processing_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE request_id=? AND tenant_id=? AND user_id=? AND status='PLANNING' AND EXISTS(SELECT 1 FROM agent_conversation c WHERE c.id=r.conversation_id AND c.status='active' AND NOT EXISTS(SELECT 1 FROM conversation_erasure e WHERE e.conversation_id=c.id))",
                interrupted?"INTERRUPTED":"FAILED",safe,requestId,user.tenantId(),user.userId());
    }
    /** 读取只派生失租状态，不抢占执行者、不修改记录，也不把未知结果当成可以重做。 */
    public static String status(Map<String,Object> row) {
        String status=Objects.toString(row.get("status"),"");
        if("PLANNING".equals(status)&&(row.get("processing_until")==null||!QuerySnapshotStore.instant(row.get("processing_until")).isAfter(Instant.now())))return "INTERRUPTED";
        return status;
    }
    private static void validKey(String key) {if(key==null||!key.matches("[a-zA-Z0-9_-]{8,100}"))throw new ApiException(422,"聊天请求号须为8至100位字母、数字、下划线或短横线");}
}
