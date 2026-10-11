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
    /** 提交前完成当前范围和可选快照核验，同请求号重试回放原任务，不自动重建失败分析。 */
    public Map<String,Object> submit(CurrentUser user,AnalysisContract.Request request,String key){var source=knowledge.snapshot(user,request.companyCode(),request.reportId());
        if(request.snapshotId()!=null)AnalysisEngine.requireScope(request,workspace.require(user,request.snapshotId()));
        var config=engine.configuration();if(!(config.get("model") instanceof Map<?,?> model)||!Boolean.TRUE.equals(model.get("configured")))throw new ApiException(503,"真实分析模型尚未配置");
        var row=repository.submit(user,request,key,source.fingerprint(),engine.configurationHash());return get(user,row.get("id").toString());}
    /** 当前用户的最近有权任务；不返回已失效资料所支撑的历史问题或结论。 */
    public List<Map<String,Object>> list(CurrentUser user){knowledge.current(user);var result=new ArrayList<Map<String,Object>>();
        for(var row:repository.recent(user))try{require(user,row);var item=new LinkedHashMap<>(view(row,false));result.add(item);}catch(ApiException denied){if(!Set.of(403,404,409,410).contains(denied.getCode()))throw denied;}
        return result;
    }
    /** 历史详情逐次核对当前知识版本和基础快照；失效时返回明确错误，不降级展示旧答案。 */
    public Map<String,Object> get(CurrentUser user,String id){var row=repository.require(knowledge.current(user),id);require(user,row);return view(row,true);}
    /** 取消原任务，终态再次读取仍不发起新模型调用。 */
    public Map<String,Object> cancel(CurrentUser user,String id){knowledge.current(user);repository.cancel(user,id);return get(user,id);}
    /** 任一支持行明细的成功步骤可稳定翻页；只重算选择，不访问变化后的来源业务系统。 */
    public Map<String,Object> page(CurrentUser user,String id,int step,int page){var row=repository.require(knowledge.current(user),id);var source=require(user,row);var request=AnalysisContract.decode(row.get("request_json").toString(),AnalysisContract.Request.class);
        if(row.get("plan_json")==null)throw new ApiException(409,"分析计划尚未通过复核");var plan=AnalysisContract.decode(row.get("plan_json").toString(),AnalysisContract.Plan.class);AnalysisContract.validate(request,plan,com.example.report.assistant.BusinessFields.forDomain(com.example.report.assistant.BusinessQuery.Domain.REPORT,source.report().fields()));
        var result=engine.page(user,request,plan,steps(row),step,page);require(user,row);return result;}
    /** 当前目录、词典、有效规则和知识发布版本必须与提交时一致，不用缓存授权掩盖变化。 */
    KnowledgeService.Snapshot require(CurrentUser user,Map<String,Object> row){var current=knowledge.current(user);var source=knowledge.snapshot(current,row.get("company_code").toString(),row.get("report_id").toString());
        if(!source.fingerprint().equals(row.get("scope_hash")))throw new ApiException(409,"分析依据的目录、词典、规则或知识版本已变化，请重新分析");engine.requireSources(current,steps(row),false);return source;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> steps(Map<String,Object> row){return (List<Map<String,Object>>)AnalysisRepository.json(row,"steps_json");}
    private static Map<String,Object> view(Map<String,Object> row,boolean detail){var result=new LinkedHashMap<String,Object>();for(String name:List.of("id","status","stage","reason","message"))result.put(name,row.get(name));result.put("version",row.get("row_version"));result.put("request",AnalysisRepository.json(row,"request_json"));
        for(String field:List.of("created_at","started_at","finished_at","expires_at"))result.put(field,row.get(field)==null?null:QuerySnapshotStore.instant(row.get(field)).toString());
        if(detail){result.put("plan",AnalysisRepository.json(row,"plan_json"));result.put("steps",AnalysisRepository.json(row,"steps_json"));result.put("answer",AnalysisRepository.json(row,"answer_json"));result.put("usage",AnalysisRepository.json(row,"usage_json"));}return result;}
}
