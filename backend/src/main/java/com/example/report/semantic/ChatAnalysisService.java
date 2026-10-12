package com.example.report.semantic;

import com.example.report.analysis.*;
import com.example.report.assistant.*;
import com.example.report.common.*;
import com.example.report.conversation.*;
import com.example.report.permission.CurrentUser;
import com.example.report.workspace.*;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 统一聊天与只读分析的外层协调器；绑定本轮原文和可信查询来源，冻结复核依据，再以原轮次创建且关联唯一子任务。
 * 不把分析引擎注入业务助手，不复制答案进普通文本；恢复和历史展示逐次核对当前权限、资料、配置与快照。
 */
@Service
public class ChatAnalysisService {
    private final AnalysisService analyses;
    private final QueryWorkspaceService workspace;
    private final ChatTurnReceiptStore receipts;
    private final ConversationService conversations;

    public ChatAnalysisService(AnalysisService analyses,QueryWorkspaceService workspace,ChatTurnReceiptStore receipts,ConversationService conversations) {
        this.analyses=analyses;this.workspace=workspace;this.receipts=receipts;this.conversations=conversations;
    }

    /**
     * 仅提供当前成功、单公司单报表的完整查询焦点；失效来源只影响快照接续，不阻止明确的新知识或当前数据请求。
     * @return 三个规划阶段共用的不透明来源上下文，不包含真实快照标识
     */
    public Map<String,Object> context(CurrentUser user,DialogueState state) {
        try {
            var source=source(user,state);var result=source.result();var query=result.query();
            return Map.of("available",true,"source",Map.of("sourceRef",sourceRef(state),"companyCode",query.companyCode(),
                    "reportId",query.reportIds().get(0),"query",query,"observedAt",result.observedAt(),
                    "expiresAt",result.snapshot().expiresAt(),"completeness",result.snapshot().completeness()));
        } catch(ApiException unavailable) {
            if(!Set.of(403,404,409,410,422).contains(unavailable.getCode()))throw unavailable;
            return Map.of("available",false,"source",Map.of());
        }
    }
    /** 当前来源引用同时绑定实际快照、查询轮次和授权版本，同样条件的新查询不会沿用旧引用。 */
    static String sourceRef(DialogueState state) {
        return "analysis-source-"+Digests.sha256(JsonUtil.toJson(List.of(state.getBusinessSnapshotId(),state.getBusinessQueryRef(),state.getBusinessPermissionVersion()))).substring(0,24);
    }
    private QuerySnapshotStore.Run source(CurrentUser user,DialogueState state) {
        var query=state.getBusinessQuery();
        if(!"BUSINESS_QUERY".equals(state.getAssistantFocus())||state.isBusinessUnresolved()||state.isUnresolvedRequest()
                ||!state.isBusinessSnapshotComplete()||state.getBusinessSnapshotId()==null||state.getBusinessQueryRef()==null
                ||!user.permissionVersion().equals(state.getBusinessPermissionVersion())||query==null
                ||query.domain()!=BusinessQuery.Domain.REPORT||query.reportIds().size()!=1||query.companyCode()==null)
            throw new ApiException(422,"没有可接续的完整单公司单报表查询，请先明确查询或分析范围");
        var source=workspace.requireComplete(user,state.getBusinessSnapshotId());
        if(source.result().snapshot()==null||source.result().observedAt()==null||source.result().snapshot().expiresAt()==null||!"COMPLETE".equals(source.result().snapshot().completeness())
                ||!state.getBusinessSnapshotId().equals(source.result().snapshot().id())||!query.equals(source.result().query()))
            throw new ApiException(409,"原查询来源或完整性已变化，请重新明确查询");
        return source;
    }
    /** 草稿与独立复核的只读预检；问题取本轮完整原文，任何来源引用都要重新匹配并核验，不能创建任务。 */
    public Map<String,Object> preflight(CurrentUser user,DialogueState state,AnalysisDirective directive,String message) {
        var prepared=prepare(user,state,directive,message);
        return Map.of("companyCode",prepared.request().companyCode(),"reportId",prepared.request().reportId(),
                "mode",prepared.request().mode().name(),"sourceRef",Objects.toString(directive.sourceRef(),""),
                "readOnly",true,"submissionPrepared",true);
    }
    private AnalysisService.Prepared prepare(CurrentUser user,DialogueState state,AnalysisDirective directive,String message) {
        String snapshot=null;
        if(directive.sourceRef()!=null) {
            var source=source(user,state);var query=source.result().query();
            if(!sourceRef(state).equals(directive.sourceRef())||!Objects.equals(query.companyCode(),directive.companyCode())
                    ||!query.reportIds().equals(List.of(directive.reportId())))throw new ApiException(422,"分析来源与当前查询的公司、报表或引用不一致");
            snapshot=source.result().snapshot().id();
        }
        return analyses.prepare(user,new AnalysisContract.Request(directive.companyCode(),directive.reportId(),message,directive.mode(),snapshot));
    }
    /**
     * 完整计划已独立复核后再预检一次，在会话租约内单独保存READY，然后原子创建子任务、关联回执并追加稳定卡片。
     * @return 五字段任务引用；答案只经分析详情接口动态读取
     */
    public Map<String,Object> submitReviewed(CurrentUser user,String id,String requestId,String message,String model,
            AssistantPlan plan,DialogueStore.Session session,Runnable guard,long started) {
        var prepared=prepare(user,session.state(),plan.analysis(),message);
        session.fenced(()->{
            guard.run();receipts.ready(user,requestId,prepared);
            var state=session.state();bindScope(user,state,prepared.request());
            state.setAssistantRoute(plan.route().name());state.setAssistantFocus(plan.route().name());
            state.setPhase(DialogueState.Phase.READY);state.setLastReason(null);state.setUnresolvedRequest(false);
            var recent=new ArrayList<>(state.getRecentUserMessages());recent.add(com.example.report.operations.SensitiveData.text(message));
            state.setRecentUserMessages(List.copyOf(recent.subList(Math.max(0,recent.size()-4),recent.size())));
            session.save();session.record(requestId,message,null,"ASSISTANT_"+plan.route().name()+"_READY",null,model,(System.nanoTime()-started)/1_000_000);
            return null;
        });
        // READY与任务关联分两次短事务；中间中断时只有显式核对能重用可信请求，不能再次调用统一规划器。
        return submitReady(user,requestId,session,guard);
    }
    /**
     * 显式核对只复用已落库的可信原文和指纹；任务创建、关联和卡片共事务，任何一步失败全部回滚到READY。
     */
    public Map<String,Object> submitReady(CurrentUser user,String requestId,DialogueStore.Session session,Runnable guard) {
        try{return session.fenced(()->{
            guard.run();var row=receipts.requireRequest(user,requestId);
            if("SUBMITTED".equals(ChatTurnReceiptStore.status(row)))return reference(user,row);
            if(!"READY".equals(ChatTurnReceiptStore.status(row)))throw new ApiException(409,"本轮没有可以核对提交的可信分析请求");
            var prepared=prepared(row);var task=analyses.submitPrepared(user,prepared,"analysis-"+requestId);
            String runId=task.get("id").toString();receipts.submitted(user,requestId,runId);
            var reference=reference(requestId,runId,prepared.request());
            conversations.logAnalysisTask(row.get("conversation_id").toString(),user.userId(),requestId,reference);
            return reference;
        });}catch(AnalysisService.PreparedInvalidated invalidated){
            // 原提交事务已经回滚，单独结束原requestId回执；仍检查租约，连接断开不应保留永久READY循环。
            // 租约仅串行化当前写入，不代表旧轮次仍拥有当前会话状态；迟到核对不得污染后续成功查询的焦点或快照。
            // 即使失败属于最新轮次，界面也从回执终态和原因展示结果，不借修改共享DialogueState表达任务失败。
            session.fenced(()->{receipts.invalidateReady(user,requestId,invalidated.getMessage());return null;});throw invalidated;
        }
    }
    private static AnalysisService.Prepared prepared(Map<String,Object> row) {
        if(row.get("trusted_request_json")==null||row.get("scope_hash")==null||row.get("config_hash")==null)
            throw new AnalysisService.PreparedInvalidated(410,"本轮可信分析提交依据已不可用，请发送新的完整问题");
        try{return new AnalysisService.Prepared(AnalysisContract.decode(row.get("trusted_request_json").toString(),AnalysisContract.Request.class),row.get("scope_hash").toString(),row.get("config_hash").toString());}
        catch(ApiException invalid){throw new AnalysisService.PreparedInvalidated(422,"本轮可信分析提交依据不符合当前协议，请发送新的完整问题");}
    }
    /** 知识和分析取得的范围同样纳入会话历史权限边界，不能只检查最后一张查询卡片。 */
    private static void bindScope(CurrentUser user,DialogueState state,AnalysisContract.Request request) {
        state.setBusinessPermissionVersion(user.permissionVersion());
        var reports=new TreeSet<>(state.getBusinessReportIds());reports.add(request.reportId());state.setBusinessReportIds(List.copyOf(reports));
        var companies=new TreeSet<>(state.getBusinessCompanyCodes());companies.add(request.companyCode());state.setBusinessCompanyCodes(List.copyOf(companies));
    }
    /** 当前授权回执投影；GET只读，过期规划返回明确中断，已提交任务失效时隐藏引用和答案。 */
    public Map<String,Object> receipt(CurrentUser user,Map<String,Object> row) {
        String status=ChatTurnReceiptStore.status(row);Map<String,Object> task=null;String message=Objects.toString(row.get("message"),null);
        if("READY".equals(status))try{analyses.revalidatePrepared(user,prepared(row));}
        catch(AnalysisService.PreparedInvalidated invalidated){status="FAILED";message=invalidated.getMessage();}
        if("SUBMITTED".equals(status))try{task=reference(user,row);}catch(ApiException unavailable){
            if(!Set.of(403,404,409,410,422).contains(unavailable.getCode()))throw unavailable;message="原分析任务已不可读取，请发送新的完整问题";
        }
        if(message==null)message=switch(status){
            case "RECEIVED"->"服务器已接收，尚未开始处理；可核对本次提交";
            case "PLANNING"->"原问题仍在处理中，请稍后核对";
            case "READY"->"分析方案已复核；可核对并提交原方案";
            case "INTERRUPTED"->"原问题处理中断，未重新调用模型；请查看历史并发送新的完整问题";
            case "COMPLETED"->"本轮已完成，可查看原会话结果";
            default->null;
        };
        var view=new LinkedHashMap<String,Object>();view.put("requestId",row.get("request_id"));view.put("conversationId",row.get("conversation_id"));view.put("clientKey",row.get("client_key"));
        view.put("status",status);view.put("canReconcile",Set.of("RECEIVED","READY").contains(status));view.put("message",message);
        view.put("historyReady",receipts.historyReady(user,row.get("conversation_id").toString()));
        view.put("expiresAt",QuerySnapshotStore.instant(row.get("expires_at")).toString());view.put("analysisTask",task);return view;
    }
    /** 读取任务前核对回执中的请求/范围/子任务关系；不能信任历史载荷或前端提交的runId。 */
    private Map<String,Object> reference(CurrentUser user,Map<String,Object> row) {
        String requestId=row.get("request_id").toString();String runId=Objects.toString(row.get("analysis_run_id"),"");
        var prepared=prepared(row);var expected=prepared.request();var task=analyses.getChatTask(user,runId,requestId,prepared);
        var actual=AnalysisContract.decode(JsonUtil.toJson(task.get("request")),AnalysisContract.Request.class);
        if(!expected.equals(actual))throw new ApiException(409,"聊天与分析任务的关联依据不一致");
        return reference(requestId,runId,actual);
    }
    private static Map<String,Object> reference(String requestId,String runId,AnalysisContract.Request request) {
        return Map.of("requestId",requestId,"runId",runId,"mode",request.mode().name(),"companyCode",request.companyCode(),"reportId",request.reportId());
    }
    /**
     * 历史卡片只返回与原会话—轮次—任务完全匹配的五字段引用；不可信或失效载荷被清空，防止缓存答案和伪造引用出站。
     */
    public MessageView history(CurrentUser user,String id,MessageView message) {
        if(!"analysis_task".equals(message.cardType()))return message;
        try {
            var payload=message.payload();if(payload==null||!(payload.get("requestId") instanceof String requestId))throw ApiException.notFound("分析卡片不可读取");
            var row=receipts.requireRequest(user,requestId);
            if(!id.equals(row.get("conversation_id"))||!"SUBMITTED".equals(ChatTurnReceiptStore.status(row)))throw ApiException.notFound("分析卡片不可读取");
            var trusted=reference(user,row);if(!trusted.equals(payload))throw ApiException.notFound("分析卡片不可读取");
            return new MessageView(message.id(),message.role(),null,message.cardType(),trusted,null,null,message.createdAt(),null,null,requestId);
        } catch(ApiException unavailable) {
            if(!Set.of(403,404,409,410,422).contains(unavailable.getCode()))throw unavailable;
            return new MessageView(message.id(),message.role(),null,message.cardType(),Map.of(),null,null,message.createdAt(),"UNAVAILABLE","原分析任务已不可读取",null);
        }
    }
}
