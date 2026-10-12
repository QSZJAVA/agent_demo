package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.config.ResourceQuotaService;
import com.example.report.investigation.InvestigationJson;
import com.example.report.permission.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 管理员语义试查入口；复用分析RAG和真实模型，不生成回答、不读业务行，每次调用前后重新核验维护权限与发布依据。 */
@Service
public class KnowledgeRagPreviewService {
    private final KnowledgeService knowledge;private final PermissionService permissions;private final ObjectProvider<AnalysisModel> models;private final ResourceQuotaService quotas;
    public KnowledgeRagPreviewService(KnowledgeService knowledge,PermissionService permissions,ObjectProvider<AnalysisModel> models,ResourceQuotaService quotas){this.knowledge=knowledge;this.permissions=permissions;this.models=models;this.quotas=quotas;}
    /**
     * 语义试查结果，原文仅属于本次已授权读取，没有永久访问权。
     * @param companyCode 本次实际维护范围的公司代码
     * @param reportId 本次实际维护范围的报表标识
     * @param question 用户实际试查原文，不存入任务
     * @param referenceTime 本次发布依据快照的UTC观测时刻
     * @param fingerprint 本次目录、词典、规则、有效资料及分块的稳定摘要
     * @param result 与分析执行共用的实际检索结果及语义轨迹
     * @param usage 最多两次真实模型调用的阶段、状态、耗时和token用量；缺失用量保留未知
     */
    public record Preview(String companyCode,String reportId,String question,Instant referenceTime,String fingerprint,Map<String,Object> result,List<Map<String,Object>> usage) { }

    /**
     * 执行一次显式语义试查；最多两次调用、80秒，与分析共用配额，失败不回退至词项试查或上一次成功结果。
     * @param user 当前认证会话解析的用户，须有范围内管理员权限
     * @param request 严格解码的范围与问题，不能包含客户端来源或检索预算
     * @return 最终再次核对权限、版本及配置后取得的候选及实际计量
     */
    public Preview preview(CurrentUser user,KnowledgeService.SearchRequest request) {
        permissions.requireAdmin(knowledge.current(user));var before=knowledge.snapshot(user,request.companyCode(),request.reportId());
        var model=models.getIfAvailable();if(model==null||!Boolean.TRUE.equals(model.configuration().get("configured")))throw new ApiException(503,"真实语义检索模型尚未配置");
        String config=configurationHash(model),attempt=JsonUtil.newId();long started=System.nanoTime();var usage=new ArrayList<Map<String,Object>>();int failureCode=0;
        try(var permit=quotas.acquire(user,"analysis",List.of(request.reportId()))) {
            Runnable guard=()->{ResourceQuotaService.check(permit);permissions.requireAdmin(knowledge.current(user));var now=knowledge.snapshot(user,request.companyCode(),request.reportId());
                if(!before.fingerprint().equals(now.fingerprint()))throw new ApiException(409,"语义试查期间知识依据已变化，请重新试查");
                if(!config.equals(configurationHash(model)))throw new ApiException(409,"语义检索配置已变化，请重新试查");remaining(started);};
            var found=KnowledgeRag.search(request.question(),List.of(new KnowledgeRag.Topic(0,request.question())),before,(stage,instructions,input,schema)->{
                guard.run();if(usage.size()>=2)throw new ApiException(422,"语义试查模型调用达到两次上限");
                var entry=new LinkedHashMap<String,Object>();entry.put("number",usage.size()+1);entry.put("stage",stage);entry.put("status","STARTED");usage.add(entry);long callStart=System.nanoTime();
                try {var reply=model.call(instructions,input,schema,remaining(started));entry.put("status","RESPONDED");entry.put("model",reply.model());entry.put("elapsedMs",reply.elapsedMs());
                    entry.put("inputTokens",reply.usage().inputTokens());entry.put("outputTokens",reply.usage().outputTokens());entry.put("cachedInputTokens",reply.usage().cachedInputTokens());entry.put("usageComplete",reply.usage().complete());guard.run();return reply.json();}
                catch(RuntimeException error){
                    if("RESPONDED".equals(entry.get("status"))){entry.put("validation","REJECTED");entry.put("reason",error instanceof ApiException api?AnalysisEvidence.safeText(api.getMessage()):"模型响应后的状态核对未完成");}
                    else {entry.put("status","FAILED");entry.put("elapsedMs",(System.nanoTime()-callStart)/1_000_000);entry.put("inputTokens",null);entry.put("outputTokens",null);entry.put("cachedInputTokens",null);entry.put("usageComplete",false);}
                    throw error;}
            });
            guard.run();return new Preview(request.companyCode(),request.reportId(),request.question(),before.referenceTime(),before.fingerprint(),found.get(0).view(),List.copyOf(usage));
        } catch(RuntimeException failure) {
            failureCode=failure instanceof ApiException api?api.getCode():500;
            if(!usage.isEmpty()&&"RESPONDED".equals(usage.get(usage.size()-1).get("status"))){usage.get(usage.size()-1).put("validation","REJECTED");}
            throw failure;
        } finally {
            // 试查不存问题或候选；失败仍保留已发生调用的匿名用量，403/409不能借诊断通道返回旧依据。
            var event=Map.of("attemptId",attempt,"outcome",failureCode==0?"COMPLETED":"FAILED","code",failureCode,"elapsedMs",(System.nanoTime()-started)/1_000_000,"usage",diagnosticUsage(usage));
            var logger=org.slf4j.LoggerFactory.getLogger(getClass());if(failureCode==0)logger.info("knowledge_rag_preview {}",JsonUtil.toJson(event));else logger.warn("knowledge_rag_preview {}",JsonUtil.toJson(event));
        }
    }
    /** 诊断字段用白名单投影，排除异常原文、问题、来源编号和正文；真实未知用量仍保留null。 */
    static List<Map<String,Object>> diagnosticUsage(List<Map<String,Object>> usage){return usage.stream().map(entry->{var item=new LinkedHashMap<String,Object>();for(String key:List.of("number","stage","status","elapsedMs","inputTokens","outputTokens","cachedInputTokens","usageComplete","validation"))if(entry.containsKey(key))item.put(key,entry.get(key));return (Map<String,Object>)item;}).toList();}
    private static String configurationHash(AnalysisModel model){return InvestigationJson.hash(Map.of("model",model.configuration(),"retrieval",KnowledgeRag.configuration()));}
    private static Duration remaining(long started){long millis=80_000-(System.nanoTime()-started)/1_000_000;if(millis<=0)throw new ApiException(408,"语义试查已达到80秒时限，请缩小问题后重试");return Duration.ofMillis(Math.min(40_000,millis));}
}
