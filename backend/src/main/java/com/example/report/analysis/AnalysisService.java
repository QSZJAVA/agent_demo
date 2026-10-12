package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.permission.*;
import com.example.report.workspace.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 分析工作区入口和历史读取授权；刷新只读取原任务，口径、资料或权限变化后不能发布或回看旧结论。 */
@Service
public class AnalysisService {
    private final KnowledgeService knowledge;private final AnalysisRepository repository;private final AnalysisEngine engine;private final QueryWorkspaceService workspace;
    public AnalysisService(KnowledgeService knowledge,AnalysisRepository repository,AnalysisEngine engine,QueryWorkspaceService workspace){this.knowledge=knowledge;this.repository=repository;this.engine=engine;this.workspace=workspace;}
    /**
     * 服务器预检后冻结的提交依据；仅在本轮权限与执行契约仍相同时使用，不表示已经创建任务。
     * @param request 本轮完整原文、范围、执行模式及明确选择的快照
     * @param scopeHash 目录、词典、规则和知识发布版本的范围摘要
     * @param configHash 模型、Schema、提示和预算的执行摘要
     */
    public record Prepared(AnalysisContract.Request request,String scopeHash,String configHash) {
        public Prepared {if(request==null||scopeHash==null||!scopeHash.matches("[a-f0-9]{64}")||configHash==null||!configHash.matches("[a-f0-9]{64}"))throw new ApiException(422,"分析提交依据不完整");}
    }
    /** 已复核请求的固定依据失效；与服务暂不可用、配额或租约竞争区分，原方案不能无限核对重试。 */
    public static class PreparedInvalidated extends ApiException {
        public PreparedInvalidated(int code,String message){super(code,message);}
    }
    /** 只读预检可在规划和独立复核中重复调用；不创建任务、不读取来源业务行，也不触发模型。 */
    public Prepared prepare(CurrentUser user,AnalysisContract.Request request){var source=knowledge.snapshot(user,request.companyCode(),request.reportId());
        if(request.snapshotId()!=null)AnalysisEngine.requireScope(request,workspace.requireComplete(user,request.snapshotId()));
        var config=engine.configuration();if(!(config.get("model") instanceof Map<?,?> model)||!Boolean.TRUE.equals(model.get("configured")))throw new ApiException(503,"真实分析模型尚未配置");
        return new Prepared(request,source.fingerprint(),com.example.report.investigation.InvestigationJson.hash(config));}
    /** 提交前完成当前范围和可选快照核验，同请求号重试回放原任务，不自动重建失败分析。 */
    public Map<String,Object> submit(CurrentUser user,AnalysisContract.Request request,String key){return create(user,prepare(user,request),key);}
    /**
     * 明确核对已复核请求时重查全部冻结依据；任何变化都拒绝，不能借恢复改成当前数据或重新解释原文。
     * @param user 原提交者的当前身份
     * @param expected 在有效会话租约中保存的可信请求与指纹
     * @param key 原轮次确定生成的子分析幂等键
     * @return 原任务或仅一次创建的任务；调用方可在同一个短事务中关联会话卡片
     */
    public Map<String,Object> submitPrepared(CurrentUser user,Prepared expected,String key){return create(user,revalidatePrepared(user,expected),key);}
    /** 只读核对冻结依据，供明确提交和GET回执共同使用；不可恢复的范围/版本错误有独立类型，瞬时故障仍可核对原键。 */
    public Prepared revalidatePrepared(CurrentUser user,Prepared expected) {
        Prepared current;
        try{current=prepare(user,expected.request());}
        catch(ApiException unavailable){if(Set.of(403,404,409,410,422).contains(unavailable.getCode()))throw new PreparedInvalidated(unavailable.getCode(),unavailable.getMessage());throw unavailable;}
        if(!current.scopeHash().equals(expected.scopeHash())||!current.configHash().equals(expected.configHash()))
            throw new PreparedInvalidated(409,"待提交分析的知识依据或执行配置已变化，请重新发送完整问题");
        return current;
    }
    private Map<String,Object> create(CurrentUser user,Prepared prepared,String key){var row=repository.submit(user,prepared.request(),key,prepared.scopeHash(),prepared.configHash());return get(user,row.get("id").toString());}
    /** 当前用户的最近有权任务；不返回已失效资料所支撑的历史问题或结论。 */
    public List<Map<String,Object>> list(CurrentUser user){knowledge.current(user);var result=new ArrayList<Map<String,Object>>();
        for(var row:repository.recent(user))try{require(user,row);var item=new LinkedHashMap<>(view(row,false));result.add(item);}catch(ApiException denied){if(!Set.of(403,404,409,410).contains(denied.getCode()))throw denied;}
        return result;
    }
    /** 历史详情逐次核对当前知识版本和基础快照；失效时返回明确错误，不降级展示旧答案。 */
    public Map<String,Object> get(CurrentUser user,String id){var row=repository.require(knowledge.current(user),id);require(user,row);return view(row,true);}
    /**
     * 聊天历史额外核对稳定子请求号和全部冻结依据，不能把同内容的另一个独立任务冒充原轮次任务。
     * @param requestId 原会话接收时生成的服务端轮次
     * @param expected 原轮次READY阶段持久化的可信请求及范围、配置摘要
     */
    public Map<String,Object> getChatTask(CurrentUser user,String id,String requestId,Prepared expected) {
        var row=repository.require(knowledge.current(user),id);
        if(!("analysis-"+requestId).equals(row.get("request_key"))||!expected.scopeHash().equals(row.get("scope_hash"))
                ||!expected.configHash().equals(row.get("config_hash"))||!expected.request().equals(AnalysisContract.decode(row.get("request_json").toString(),AnalysisContract.Request.class)))
            throw new ApiException(409,"聊天与分析任务的关联依据不一致");
        require(user,row);return view(row,true);
    }
    /** 取消原任务，终态再次读取仍不发起新模型调用。 */
    public Map<String,Object> cancel(CurrentUser user,String id){knowledge.current(user);repository.cancel(user,id);return get(user,id);}
    /** 任一支持行明细的成功步骤可稳定翻页；只重算选择，不访问变化后的来源业务系统。 */
    public Map<String,Object> page(CurrentUser user,String id,int step,int page){var row=repository.require(knowledge.current(user),id);var source=require(user,row);var request=AnalysisContract.decode(row.get("request_json").toString(),AnalysisContract.Request.class);
        if(row.get("plan_json")==null)throw new ApiException(409,"分析计划尚未通过复核");var plan=AnalysisContract.decode(row.get("plan_json").toString(),AnalysisContract.Plan.class);AnalysisContract.validate(request,plan,com.example.report.assistant.BusinessFields.forDomain(com.example.report.assistant.BusinessQuery.Domain.REPORT,source.report().fields()));
        var result=engine.page(user,request,plan,steps(row),step,page);require(user,row);return result;}
    /** 当前目录、词典、有效规则和知识发布版本必须与提交时一致，不用缓存授权掩盖变化。 */
    KnowledgeService.Snapshot require(CurrentUser user,Map<String,Object> row){repository.requireConversation(row.get("id").toString());var current=knowledge.current(user);var source=knowledge.snapshot(current,row.get("company_code").toString(),row.get("report_id").toString());
        if(!source.fingerprint().equals(row.get("scope_hash")))throw new ApiException(409,"分析依据的目录、词典、规则或知识版本已变化，请重新分析");
        if(!engine.configurationHash().equals(row.get("config_hash")))throw new ApiException(409,"分析执行配置已变化，请重新分析");
        var request=AnalysisContract.decode(row.get("request_json").toString(),AnalysisContract.Request.class);
        if(request.snapshotId()!=null)AnalysisEngine.requireScope(request,workspace.requireComplete(current,request.snapshotId()));
        engine.requireSources(current,steps(row),false);return source;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> steps(Map<String,Object> row){return (List<Map<String,Object>>)AnalysisRepository.json(row,"steps_json");}
    private static Map<String,Object> view(Map<String,Object> row,boolean detail){var result=new LinkedHashMap<String,Object>();for(String name:List.of("id","status","stage","reason","message"))result.put(name,row.get(name));result.put("version",row.get("row_version"));result.put("request",AnalysisRepository.json(row,"request_json"));
        for(String field:List.of("created_at","started_at","finished_at","expires_at"))result.put(field,row.get(field)==null?null:QuerySnapshotStore.instant(row.get(field)).toString());
        if(detail){result.put("plan",AnalysisRepository.json(row,"plan_json"));result.put("steps",AnalysisRepository.json(row,"steps_json"));result.put("answer",AnalysisRepository.json(row,"answer_json"));result.put("usage",AnalysisRepository.json(row,"usage_json"));}return result;}
}
