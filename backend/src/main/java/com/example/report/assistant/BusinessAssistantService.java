package com.example.report.assistant;

import com.example.report.agent.AgentEvent;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.*;
import com.example.report.conversation.ConversationService;
import com.example.report.mcp.BusinessMcpClient;
import com.example.report.permission.CurrentUser;
import com.example.report.semantic.DialogueStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;

/** 统一助手只读分支；沿用外层对话租约，查询状态与派单状态分离，事实卡片由业务服务生成并持久化。 */
@Service
public class BusinessAssistantService {
    private final AssistantPlanner planner;
    private final ReportCatalogService catalog;
    private final ObjectProvider<BusinessMcpClient> clients;
    private final ConversationService conversations;
    private final com.example.report.permission.PermissionService permissions;
    public BusinessAssistantService(AssistantPlanner planner,ReportCatalogService catalog,ObjectProvider<BusinessMcpClient> clients,ConversationService conversations,com.example.report.permission.PermissionService permissions) {
        this.planner=planner;this.catalog=catalog;this.clients=clients;this.conversations=conversations;this.permissions=permissions;
    }
    /**
     * 处理只读查询、帮助和澄清；返回false才允许进入既有派单规划，模型错误不回退到派单路径。
     * @param user 当前身份
     * @param id 会话标识
     * @param requestId 本轮请求标识，供证据关联
     * @param message 本轮用户原文
     * @param model 当前配置的实际模型名称，用于本轮审计记录
     * @param session 已持有的会话租约
     * @param guard 配额、连接和租约守卫
     * @param emit 有序 SSE 输出
     * @return true表示本轮已在通用助手分支处理
     */
    public boolean handle(CurrentUser user,String id,String requestId,String message,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit) {
        long started=System.nanoTime();var state=session.state();
        AssistantPlan plan;
        try {
            catalog.refreshForValidation();
            plan=planner.plan(message,state,catalog.visibleReports(user),user.companies());guard.run();
        } catch(Exception failure) {
            // 路由本身不可靠时也不能让下一轮省略建单沿用旧意图；明确的只读接口失败则只影响查询上下文。
            state.setUnresolvedRequest(true);
            if("DISPATCH".equals(state.getAssistantFocus()))state.setPhase(com.example.report.semantic.DialogueState.Phase.CLARIFY);
            state.setBusinessUnresolved(true);state.setAssistantRoute("CLARIFY");
            finish(user,id,requestId,message,"本轮请求未能可靠解析，未应用任何修改。请明确查询对象、范围或具体操作。",model,session,guard,emit,started);return true;
        }
        if(plan.route()==AssistantPlan.Route.DISPATCH) {state.setAssistantRoute("DISPATCH");state.setAssistantFocus("DISPATCH");return false;}
        boolean previousDispatch="DISPATCH".equals(state.getAssistantFocus());
        state.setAssistantRoute(plan.route().name());
        String reply;
        try {
            reply=switch(plan.route()) {
                case HELP -> "我是业务助手，可以查询报表数据、派单记录、工单进度和工单总结，也可以准备派单清单。你可以问“销售报表金额大于五万元的有哪些”“查询失败的派单记录”“WO-DEMO-001 到哪个环节了”“总结 A 公司工单”。工单环节与审批人为明确标注的演示数据；派单仍需核对清单并点击确认。";
                case CLARIFY -> {
                    state.setBusinessUnresolved(true);
                    if(previousDispatch){state.setUnresolvedRequest(true);state.setPhase(com.example.report.semantic.DialogueState.Phase.CLARIFY);}
                    yield plan.clarification()+" 本轮未应用任何修改。";
                }
                case BUSINESS_QUERY -> {
                    // 新查询即使失败也形成业务范围边界，后续“这些”不能隐式引用之前的派单候选。
                    state.setBusinessQueryAfterPreview(true);
                    if(plan.followUp() && (state.getBusinessQuery()==null || state.isBusinessUnresolved())) throw new ApiException(422,"上一查询未完成，请重新明确查询对象和条件");
                    var result=read(user,plan.query());guard.run();
                    // 取得业务事实前先复核当前目录授权；返回响应不能扩大模型提出的查询范围。
                    state.setBusinessPermissionVersion(user.permissionVersion());state.setBusinessQuery(result.query());state.setBusinessUnresolved(false);
                    state.setAssistantFocus("BUSINESS_QUERY");
                    var historyReports=new TreeSet<>(state.getBusinessReportIds());historyReports.addAll(result.query().reportIds());state.setBusinessReportIds(List.copyOf(historyReports));
                    var historyCompanies=new TreeSet<>(state.getBusinessCompanyCodes());historyCompanies.addAll(result.query().companyCode()==null?user.companies():Set.of(result.query().companyCode()));state.setBusinessCompanyCodes(List.copyOf(historyCompanies));
                    state.setBusinessReferences(references(result));
                    session.fenced(()->{guard.run();session.save();conversations.logCard(id,user.userId(),"business_query",result,null,null);return null;});
                    emit.accept(new AgentEvent("business_query",result));
                    yield summary(result);
                }
                default -> throw new IllegalStateException();
            };
        } catch(Exception failure) {
            state.setBusinessUnresolved(true);
            reply=failure instanceof ApiException api?api.getMessage():"业务查询暂未完成，请稍后重试；本轮没有执行派单或审批。";
        }
        finish(user,id,requestId,message,reply,model,session,guard,emit,started);return true;
    }
    /** 身份由MCP客户端注入，当前全部报表先展开为明确授权集合，避免远端查询范围大于本地运营策略。 */
    public BusinessResult read(CurrentUser user,BusinessQuery query) {
        catalog.refreshForValidation();
        List<String> ids=query.reportIds().isEmpty()?catalog.visibleReports(user).stream().map(e->e.reportId()).sorted().toList():query.reportIds();
        for(String id:ids)catalog.requireVisible(user,id);
        if(query.companyCode()!=null && !user.companies().contains(query.companyCode()))throw ApiException.forbidden("公司不存在或无权访问");
        if(ids.isEmpty())throw new ApiException(422,"当前没有可查询的授权报表");
        var resolved=new BusinessQuery(query.domain(),query.view(),ids,query.companyCode(),query.conditions(),query.sortField(),query.descending(),query.page(),query.size(),query.groupBy());
        var client=clients.getIfAvailable();if(client==null)throw new ApiException(503,"业务查询需要已配置的 HTTP MCP 服务");
        BusinessResult result=client.call("business_query",user,Map.of("query",resolved),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        if(result==null || !resolved.equals(result.query()) || result.rows()==null || result.columns()==null || result.summary()==null || result.total()!=result.summary().count()
                || result.total()<0 || result.rows().size()>query.size() || JsonUtil.toJson(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>240000)
            throw new ApiException(502,"业务查询响应不完整或超过展示预算");
        for(var row:result.rows()) if(!ids.contains(row.get("reportId")) || !user.companies().contains(row.get("companyCode")))throw new ApiException(502,"业务接口返回范围外数据");
        catalog.refreshForValidation();for(String id:ids)catalog.requireVisible(user,id);
        var current=permissions.resolve(user.userId());
        if(!current.tenantId().equals(user.tenantId()) || !current.permissionVersion().equals(user.permissionVersion())) throw ApiException.forbidden("查询期间权限已变化，请重新登录后查询");
        return result;
    }
    /** 每轮事实与总结先持久化再发出完成文本；失败记录不覆盖派单预览和选择状态。 */
    private void finish(CurrentUser user,String id,String requestId,String message,String reply,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit,long started) {
        session.fenced(()->{guard.run();session.save();session.record(requestId,message,null,"ASSISTANT_"+session.state().getAssistantRoute(),null,model,(System.nanoTime()-started)/1_000_000);
            conversations.logAssistant(id,user.userId(),reply,model,null,(System.nanoTime()-started)/1_000_000);return null;});
        emit.accept(new AgentEvent(AgentEvent.TEXT,Map.of("delta",reply)));
    }
    private static List<Map<String,String>> references(BusinessResult result) {
        List<Map<String,String>> references=new ArrayList<>();
        for(int i=0;i<result.rows().size();i++) {
            var row=result.rows().get(i);var ref=new LinkedHashMap<String,String>();ref.put("displayIndex",String.valueOf((result.query().page()-1)*result.query().size()+i+1));
            for(String key:List.of("reportId","recordId","docNo","planId","requestId","orderId"))if(row.get(key)!=null)ref.put(key,row.get(key).toString());references.add(ref);
            // 当前页展示过的标量字段也是指代依据，不能只给模型编号而丢失产品或费用类型。
            // 不加入未展示记录和嵌套流程；出站仍须统一脱敏并通过模型上下文字节预算。
            for(var column:result.columns()) {
                Object value=row.get(column.name());
                if(value!=null && !(value instanceof Map<?,?>) && !(value instanceof Collection<?>))ref.putIfAbsent(column.name(),value.toString());
            }
        }
        return List.copyOf(references);
    }
    private static String summary(BusinessResult result) {
        String domain=switch(result.query().domain()){case REPORT->"报表记录";case DISPATCH->"派单条目";case WORK_ORDER->"工单";};
        String text="本次查询匹配 "+result.total()+" 条"+domain+"，当前第 "+result.query().page()+" 页展示 "+result.rows().size()+" 条。";
        if(result.query().view()==BusinessQuery.View.SUMMARY) {
            text+="状态分布："+counts(result.summary().statusCounts())+"。";
            if(!result.summary().amountsByCurrency().isEmpty())text+="金额合计："+result.summary().amountsByCurrency().entrySet().stream().map(e->e.getKey()+" "+e.getValue()).collect(java.util.stream.Collectors.joining("；"))+"。";
            if(!result.summary().pendingApprovers().isEmpty())text+="当前待审批："+counts(result.summary().pendingApprovers())+"。";
            if(!result.summary().groups().isEmpty())text+="分组："+counts(result.summary().groups())+"。";
        }
        if(result.query().domain()==BusinessQuery.Domain.WORK_ORDER) {
            if(result.query().view()==BusinessQuery.View.DETAIL && result.rows().size()==1)text+=Objects.toString(result.rows().get(0).get("workflowSummary"),"");
            text+="工单为固定演示流程，查询不会推进审批；后续环节见卡片，尚未到达的环节不代表已完成。";
        }
        return text;
    }
    private static String counts(Map<String,Integer> values){return values.isEmpty()?"暂无记录":values.entrySet().stream().map(e->e.getKey()+" "+e.getValue()+" 条").collect(java.util.stream.Collectors.joining("；"));}
}
