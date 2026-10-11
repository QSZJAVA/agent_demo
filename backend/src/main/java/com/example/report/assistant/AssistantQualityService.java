package com.example.report.assistant;

import com.example.report.common.*;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.PreviewService;
import com.example.report.operations.SensitiveData;
import com.example.report.permission.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 本人语义质量工作台；从可靠会话事件读取诊断与用户反馈，当前业务授权始终高于历史可见性。 */
@Service
public class AssistantQualityService {
    private static final String DIAGNOSTIC="assistant_diagnostic";
    private static final String FEEDBACK="assistant_feedback";
    private final JdbcTemplate jdbc;
    private final ConversationService conversations;
    private final PreviewService previews;
    public AssistantQualityService(JdbcTemplate jdbc,ConversationService conversations,PreviewService previews){this.jdbc=jdbc;this.conversations=conversations;this.previews=previews;}
    /**
     * 游标页只声明本页样本，不把局部样本推断为全租户正确率。
     * @param records 当前本人且仍有权读取的诊断
     * @param nextCursor 后续扫描游标，空表示已到末尾；可能存在空页但仍有游标
     */
    public record Page(List<Map<String,Object>> records,String nextCursor) { }
    /** 读取本人诊断；受限批次继续扫描隐藏会话，最多扫描1000项后交还游标。 */
    public Page list(CurrentUser user,Long before){
        if(before!=null && before<=0)throw new ApiException(422,"诊断游标无效");
        long cursor=before==null?Long.MAX_VALUE:before;var visible=new ArrayList<Map<String,Object>>();boolean more=false;
        for(int scanned=0;scanned<1000 && visible.size()<20;){
            var batch=rows(user,DIAGNOSTIC,cursor,100);if(batch.isEmpty()){more=false;break;}
            for(var row:batch){scanned++;cursor=((Number)row.get("id")).longValue();more=true;
                if(readable(user,row.get("conversation_id").toString()))visible.add(view(row));
                if(visible.size()==20)break;
            }
            if(visible.size()<20 && batch.size()<100){more=false;break;}
        }
        return new Page(List.copyOf(visible),more?String.valueOf(cursor):null);
    }
    /** 读取确切诊断并复查会话权限；其他用户、租户或非诊断事件统一按不存在处理。 */
    public Map<String,Object> get(CurrentUser user,long eventId){
        if(eventId<=0)throw ApiException.notFound("诊断不存在");var rows=jdbc.queryForList(select()+" AND e.id=? AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.message.toolName'))=?",user.tenantId(),user.userId(),eventId,DIAGNOSTIC);
        if(rows.isEmpty())throw ApiException.notFound("诊断不存在");var row=rows.get(0);requireReadable(user,row.get("conversation_id").toString());return view(row);
    }
    /** 反馈按确切诊断游标分页；先筛选再分页，其他轮次的反馈不能挤掉本轮证据。 */
    public Page feedback(CurrentUser user,long eventId,Long before){
        if(before!=null && before<=0)throw new ApiException(422,"反馈游标无效");
        var diagnostic=get(user,eventId);String conversation=diagnostic.get("conversationId").toString();
        var rows=jdbc.queryForList(select()+" AND e.conversation_id=? AND e.id<? AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.message.toolName'))=? AND JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.message.content')),'$.diagnosticId'))=? ORDER BY e.id DESC LIMIT 21",user.tenantId(),user.userId(),conversation,before==null?Long.MAX_VALUE:before,FEEDBACK,String.valueOf(eventId));
        var page=rows.subList(0,Math.min(20,rows.size()));
        return new Page(page.stream().map(AssistantQualityService::view).toList(),rows.size()>20?page.get(page.size()-1).get("id").toString():null);
    }
    /**
     * 在会话锁内按请求键保存一次反馈，重放相同内容返回原事件，不同内容返回409。
     * @param eventId 用户正在评价的诊断事件
     * @param input feedbackId、category及note三个字符串字段；不接收权限或模型评分
     * @return 已持久化反馈标识和明确的未审核状态
     */
    @Transactional
    public Map<String,Object> submitFeedback(CurrentUser user,long eventId,Map<String,Object> input){
        if(input==null || !input.keySet().equals(Set.of("feedbackId","category","note")) || input.values().stream().anyMatch(v->!(v instanceof String)))throw new ApiException(422,"反馈表单字段或类型无效");
        String key=(String)input.get("feedbackId"),category=(String)input.get("category"),note=(String)input.get("note");
        if(!key.matches("[a-f0-9]{32}") || !Set.of("MISUNDERSTOOD","QUERY_ADJUSTMENT").contains(category) || note.isBlank() || note.length()>1000)throw new ApiException(422,"请提供有效的反馈类型及1至1000字说明");
        var diagnostic=get(user,eventId);String conversation=diagnostic.get("conversationId").toString();
        var locked=jdbc.queryForList("SELECT id FROM agent_conversation WHERE tenant_id=? AND user_id=? AND id=? AND status='active' FOR UPDATE",user.tenantId(),user.userId(),conversation);
        if(locked.isEmpty())throw ApiException.notFound("反馈会话不存在或已关闭");
        requireReadable(user,conversation);
        // 原文只参与摘要，同一键下即使两个不同说明脱敏后相同也必须拒绝，不能把内容变更当成重放。
        var payload=Map.of("diagnosticId",String.valueOf(eventId),"feedbackId",key,"category",category,"note",SensitiveData.text(note),"reviewStatus","UNREVIEWED","requestHash",Digests.sha256(JsonUtil.toJson(List.of(category,note))));
        String eventKey=Digests.sha256(user.tenantId()+"|semantic:"+FEEDBACK+":"+conversation+":"+key);
        // 锁定读取取得等待前一提交后的最新行；普通快照读可能漏掉并发刚写入的同键不同内容。
        var prior=jdbc.queryForList("SELECT id,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.message.content')) content FROM trace_event WHERE tenant_id=? AND user_id=? AND event_key=? FOR UPDATE",user.tenantId(),user.userId(),eventKey);
        if(!prior.isEmpty()){
            if(!JsonUtil.toMap(prior.get(0).get("content").toString()).equals(payload))throw new ApiException(409,"反馈请求号已用于不同内容，请重新提交");
            return Map.of("feedbackEventId",prior.get(0).get("id").toString(),"reviewStatus","UNREVIEWED","replayed",true);
        }
        long saved=conversations.logSemanticEvidence(conversation,user.userId(),FEEDBACK,key,payload);
        return Map.of("feedbackEventId",String.valueOf(saved),"reviewStatus","UNREVIEWED","replayed",false);
    }
    private List<Map<String,Object>> rows(CurrentUser user,String kind,long before,int size){return jdbc.queryForList(select()+" AND e.id<? AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.message.toolName'))=? ORDER BY e.id DESC LIMIT ?",user.tenantId(),user.userId(),before,kind,size);}
    private static String select(){return "SELECT e.id,e.conversation_id,e.created_at,JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.message.content')) content FROM trace_event e JOIN agent_conversation c ON c.id=e.conversation_id AND c.tenant_id=e.tenant_id AND c.user_id=e.user_id WHERE e.tenant_id=? AND e.user_id=? AND c.status='active' AND e.event_type='MESSAGE' AND NOT EXISTS (SELECT 1 FROM conversation_erasure x WHERE x.conversation_id=c.id)";}
    private static Map<String,Object> view(Map<String,Object> row){Object content=row.get("content");if(content==null)throw new ApiException(410,"诊断已按留存规则清理");return Map.of("eventId",row.get("id").toString(),"conversationId",row.get("conversation_id"),"createdAt",row.get("created_at").toString(),"diagnostic",JsonUtil.toMap(content.toString()));}
    private boolean readable(CurrentUser user,String id){try{requireReadable(user,id);return true;}catch(ApiException failure){if(failure.getCode()==403 || failure.getCode()==404 || failure.getCode()==410)return false;throw failure;}}
    private void requireReadable(CurrentUser user,String id){conversations.getOwned(user,id);previews.requireConversationReadable(user,id);}
}
