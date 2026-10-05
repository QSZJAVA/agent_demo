package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.operations.SensitiveData;
import com.example.report.permission.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.report.investigation.InvestigationTypes.*;

/** 调查准入、幂等及只读恢复入口；接收请求只落库，模型和MCP由工作线程执行。 */
@Service
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationService {
    /**
     * 创建结果。
     * @param run 已创建或回放的运行；没有异常条目时为空
     * @param replayed 是否同键同负载的回放
     * @param empty 是否无可调查条目，未调用模型
     */
    public record Submission(Run run,boolean replayed,boolean empty) { }
    private final InvestigationRepository repository;
    private final InvestigationAccessPolicy access;
    private final InvestigationFacts facts;
    private final InvestigationModel model;
    private final JdbcTemplate jdbc;
    private final InvestigationWorker worker;
    public InvestigationService(InvestigationRepository repository,InvestigationAccessPolicy access,InvestigationFacts facts,InvestigationModel model,JdbcTemplate jdbc,InvestigationWorker worker) {
        this.repository=repository;this.access=access;this.facts=facts;this.model=model;this.jdbc=jdbc;this.worker=worker;
    }
    /** 规范化请求后先查幂等身份；回放不因来源状态变化而重新创建或调用模型。 */
    public Submission submit(CurrentUser actor,Request input,String key) {
        if(key==null || !key.matches("[a-zA-Z0-9_-]{16,128}")) throw new ApiException("请提供16～128字符的稳定调查幂等键");
        if(input==null || input.planId()==null || input.planId().isBlank() || input.planId().length()>64 || input.question()==null || input.question().isBlank() || input.question().length()>1000)
            throw new ApiException("请提供清单和1～1000字符的调查问题");
        List<String> inputIds=input.itemIds()==null?null:input.itemIds().stream().map(InvestigationFacts::itemId).distinct().sorted(Comparator.comparingLong(Long::parseLong)).toList();
        var canonical=new LinkedHashMap<String,Object>();canonical.put("planId",input.planId().trim());canonical.put("question",input.question().trim());canonical.put("itemIds",inputIds);
        String hash=InvestigationJson.hash(canonical);var prior=repository.byKey(actor,key);
        if(prior.isPresent()) return new Submission(view(actor,repository.replay(prior.get(),hash)),true,false);
        var plan=access.require(actor,input.planId().trim());var selected=facts.select(actor,plan.getId(),inputIds);
        if(selected.isEmpty()) return new Submission(null,false,true);
        var config=model.configuration();if(!Boolean.TRUE.equals(config.get("configured"))) throw new ApiException(503,"MODEL_UNAVAILABLE：请先补齐真实调查模型配置");
        canonical.put("question",SensitiveData.text(input.question().trim()));canonical.put("selectedItemIds",selected);canonical.put("ownerId",plan.getUserId());
        var row=repository.create(actor,canonical,key,hash,config,() -> {
            var latest=access.require(actor,plan.getId());
            var current=jdbc.queryForMap("SELECT execution_version,status FROM dispatch_plan WHERE id=? AND tenant_id=? FOR UPDATE",plan.getId(),actor.tenantId());
            facts.select(actor,plan.getId(),selected);return ((Number)current.get("execution_version")).longValue();
        });
        return new Submission(view(actor,row.run()),row.replayed(),false);
    }
    /** GET只查询已有任务，重新验证创建人和清单当前数据权限。 */
    public Run get(CurrentUser actor,String id) {return view(actor,owned(actor,id));}
    /** 页面分页选择异常条目；ID以字符串返回，管理员仍受完整清单范围复核。 */
    public Page candidates(CurrentUser actor,String planId,long after,int size) {
        if(after<0 || size<1 || size>50) throw new ApiException("候选分页参数无效");access.require(actor,planId);
        var rows=jdbc.queryForList("SELECT id,doc_no,label,status,error_code,error_message FROM dispatch_plan_item WHERE plan_id=? AND id>? AND status IN ('FAILED','SKIPPED','UNKNOWN','PENDING') ORDER BY id LIMIT ?",planId,after,size+1);
        var selected=rows.stream().limit(size).map(InvestigationService::safeRow).toList();
        return new Page(selected,rows.size()>size?selected.get(selected.size()-1).get("id").toString():null);
    }
    /** 以同范围任务ID为锚点分页；新增任务不会让后续页重复，过期锚点要求重新读取第一页。 */
    public Page list(CurrentUser actor,String plan,String cursor,int size) {
        if(size<1 || size>50 || cursor==null || (!cursor.isEmpty() && !cursor.matches("[a-zA-Z0-9_-]{1,64}"))) throw new ApiException("任务分页参数无效");
        access.require(actor,plan);var rows=repository.list(actor,plan,cursor,size);var selected=rows.stream().limit(size).map(InvestigationService::safeRow).toList();
        return new Page(selected,rows.size()>size?selected.get(selected.size()-1).get("id").toString():null);
    }
    public Page steps(CurrentUser actor,String id,long after,int size) {
        validatePage(after,size,50);owned(actor,id);var rows=repository.steps(id,after,size);var selected=rows.stream().limit(size).map(InvestigationService::safeRow).toList();
        return new Page(selected,selected.isEmpty()?Long.toString(after):Objects.toString(selected.get(selected.size()-1).get("seq")));
    }
    public Map<String,Object> evidence(CurrentUser actor,String id,String ref) {
        owned(actor,id);if(!ref.matches("E[1-9][0-9]{0,5}")) throw new ApiException("证据引用无效");return safeRow(repository.evidence(id,ref));
    }
    /** 取消原任务并中断本实例在途调用；其他实例依靠认领令牌失效停止回写。 */
    public Run cancel(CurrentUser actor,String id) {owned(actor,id);var row=repository.cancel(id);worker.stop(id);return view(actor,row);}
    private Map<String,Object> owned(CurrentUser actor,String id) {
        var row=repository.find(id);
        if(!actor.tenantId().equals(row.get("tenant_id")) || !actor.userId().equals(row.get("actor_id"))) throw ApiException.notFound("调查任务不存在");
        access.require(actor,row.get("plan_id").toString());return row;
    }
    @SuppressWarnings("unchecked")
    private Run view(CurrentUser actor,Map<String,Object> row) {
        owned(actor,row.get("id").toString());var snapshot=InvestigationRepository.json(row,"snapshot_json");var request=InvestigationRepository.json(row,"request_json");
        var items=new ArrayList<Map<String,Object>>();boolean changed=false;
        if(!snapshot.isEmpty()) {
            for(var item:(List<Map<String,Object>>)snapshot.get("items")) items.add(Map.of("itemRef",item.get("itemRef"),"itemId",item.get("itemId"),"docNo",item.get("docNo"),"status",item.get("status")));
            try {changed=!Objects.equals(row.get("source_fingerprint"),facts.snapshot(actor,row.get("plan_id").toString(),(List<String>)request.get("selectedItemIds"),false).get("fingerprint"));}
            catch(ApiException e) {throw e;}
        } else {
            int n=0;for(String id:(List<String>)request.get("selectedItemIds")) items.add(Map.of("itemRef","I"+(++n),"itemId",id));
        }
        var usage=new LinkedHashMap<>(InvestigationRepository.json(row,"usage_json"));usage.put("modelCalls",row.get("model_calls"));usage.put("toolCalls",row.get("tool_calls"));usage.put("mcpCalls",row.get("mcp_calls"));
        return new Run(row.get("id").toString(),row.get("plan_id").toString(),row.get("status").toString(),(String)row.get("stop_reason"),(String)row.get("message"),row.get("idempotency_key").toString(),items,row.get("report_json")==null?null:InvestigationRepository.json(row,"report_json"),changed,repository.activeStep(row.get("id").toString()),usage,time(row.get("created_at")),time(row.get("finished_at")));
    }
    /** 会话删除时调查数据一起清理，避免留下已撤销会话的可读证据；不是旧数据迁移。 */
    public void eraseConversation(String tenant,String conversation) {
        var ids=jdbc.queryForList("SELECT r.id FROM agent_investigation_run r JOIN dispatch_plan p ON p.id=r.plan_id WHERE r.tenant_id=? AND p.conversation_id=?",String.class,tenant,conversation);
        for(String id:ids) {repository.cancel(id);worker.stop(id);repository.delete(id);}
    }
    private static void validatePage(long cursor,int size,int max) {if(cursor<0 || cursor>Integer.MAX_VALUE || size<1 || size>max) throw new ApiException("分页参数无效");}
    static String time(Object value) {if(value==null) return null;return value.toString().replace(' ','T')+"Z";}
    static Map<String,Object> safeRow(Map<String,Object> row) {
        var safe=new LinkedHashMap<String,Object>();
        row.forEach((key,value) -> {
            String target=key.replace("_json","");
            if(value!=null && key.endsWith("_json")) safe.put(target,JsonUtil.fromJson(value.toString(),Object.class));
            else if(key.endsWith("_at")) safe.put(target,time(value));else if("id".equals(key) && value!=null) safe.put(key,value.toString());else safe.put(target,value);
        });
        @SuppressWarnings("unchecked") var cleaned=(Map<String,Object>)SensitiveData.typed(safe);return cleaned;
    }
}
