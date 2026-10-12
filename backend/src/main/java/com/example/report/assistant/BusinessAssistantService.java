package com.example.report.assistant;

import com.example.report.agent.AgentEvent;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.*;
import com.example.report.conversation.ConversationService;
import com.example.report.mcp.BusinessMcpClient;
import com.example.report.permission.CurrentUser;
import com.example.report.semantic.DialogueState;
import com.example.report.semantic.DialogueStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** 统一任务入口；在同一会话租约中规划并复核完整任务，只读查询产出事实卡片，派单任务原样交给确定性执行层。 */
@Service
@lombok.extern.slf4j.Slf4j
public class BusinessAssistantService {
    @org.springframework.beans.factory.annotation.Autowired
    private SemanticDictionaryService dictionaries;
    private final AssistantPlanner planner;
    private final ReportCatalogService catalog;
    private final ObjectProvider<BusinessMcpClient> clients;
    private final ConversationService conversations;
    private final com.example.report.permission.PermissionService permissions;
    public BusinessAssistantService(AssistantPlanner planner,ReportCatalogService catalog,ObjectProvider<BusinessMcpClient> clients,ConversationService conversations,com.example.report.permission.PermissionService permissions) {
        this.planner=planner;this.catalog=catalog;this.clients=clients;this.conversations=conversations;this.permissions=permissions;
    }
    /**
 * 处理只读查询、帮助和澄清；派单、知识与分析返回已经复核的完整计划，不允许下游再次解释原文。
     * @param user 当前身份
     * @param id 会话标识
     * @param requestId 本轮请求标识，供证据关联
     * @param message 本轮用户原文
     * @param model 当前配置的实际模型名称，用于本轮审计记录
     * @param session 已持有的会话租约
     * @param guard 配额、连接和租约守卫
     * @param emit 有序 SSE 输出
     * @param dispatchSelection 当前候选选择的只读事实及完整性，不能当作默认操作授权
     * @param validateDispatch 派单草稿的无写入预检，返回实际目标或选择结果供通用语义复核
     * @return 有值表示由外层继续执行该完整计划；空表示本轮已处理完毕
     */
    public Optional<AssistantPlan> handle(CurrentUser user,String id,String requestId,String message,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit,
            Map<String,Object> dispatchSelection,Function<DispatchDirective,Map<String,Object>> validateDispatch) {
        return handle(user,id,requestId,message,model,session,guard,emit,dispatchSelection,validateDispatch,null);
    }
    /** 结构化编辑沿用同一模型与业务预检，额外核对用户提交的完整查询，不能转成派单动作。 */
    public Optional<AssistantPlan> handle(CurrentUser user,String id,String requestId,String message,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit,
            Map<String,Object> dispatchSelection,Function<DispatchDirective,Map<String,Object>> validateDispatch,BusinessQuery requestedQuery) {
        return handle(user,id,requestId,message,model,session,guard,emit,dispatchSelection,validateDispatch,requestedQuery,null);
    }
    /** 条件编辑与候选选择都由同一预检核对，约束同时适用于模型草稿和独立复核的完整期望。 */
    public Optional<AssistantPlan> handle(CurrentUser user,String id,String requestId,String message,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit,
            Map<String,Object> dispatchSelection,Function<DispatchDirective,Map<String,Object>> validateDispatch,BusinessQuery requestedQuery,AssistantInteraction.SelectedClarification selected) {
        return handle(user,id,requestId,message,model,session,guard,emit,dispatchSelection,validateDispatch,requestedQuery,selected,Map.of(),directive->{throw new ApiException(422,"当前入口未提供知识与分析提交上下文");});
    }
    /**
     * 外层提供受控分析来源和无副作用预检，避免分析执行引擎反向依赖形成循环；模型只得到服务器引用，不能指定任意快照。
     * @param analysisContext 当前成功查询焦点的完整性与不透明来源引用，不含真实snapshotId
     * @param validateAnalysis 草稿与独立复核共用的当前范围、资料、配置及快照检查，不得创建任务
     */
    public Optional<AssistantPlan> handle(CurrentUser user,String id,String requestId,String message,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit,
            Map<String,Object> dispatchSelection,Function<DispatchDirective,Map<String,Object>> validateDispatch,BusinessQuery requestedQuery,AssistantInteraction.SelectedClarification selected,
            Map<String,Object> analysisContext,Function<AnalysisDirective,Map<String,Object>> validateAnalysis) {
        long started=System.nanoTime();var state=session.state();
        AssistantPlan plan;
        var diagnostics=new PlanningDiagnostics();SemanticDictionaryService.Snapshot dictionary=null;
        var validated=new HashMap<BusinessQuery,BusinessResult>();var attemptedRead=new boolean[]{false};
        try {
            catalog.refreshForValidation();
            var visibleReports=catalog.visibleReports(user);
            dictionary=dictionaries==null?new SemanticDictionaryService.Snapshot(List.of(),java.time.Instant.now(),Digests.sha256("[]")):dictionaries.snapshot(user,visibleReports);
            if(selected!=null && !Objects.equals(selected.dictionaryHash(),dictionary.fingerprint()))throw new ApiException(409,"澄清词典已更新，请重新发送完整问题");
            var recent=conversations.messages(user,id,null,16);
            // 本轮用户消息已先持久化，单独作为message传入；在截断之前移除重复来源，避免它既是历史又是当前授权。
            if(!recent.isEmpty() && "user".equals(recent.get(recent.size()-1).role())
                    && message.equals(recent.get(recent.size()-1).content()))recent=recent.subList(0,recent.size()-1);
            var history=recent.stream().filter(m->Set.of("user","assistant").contains(m.role()) && m.content()!=null)
                    .map(m->Map.of("role",m.role(),"content",m.content().length()>2000?m.content().substring(0,2000)+"[文本已截断]":m.content())).toList();
            plan=planner.plan(message,state,visibleReports,user.companies(),new AssistantPlanningContext(history,dispatchSelection,draft->{
                guard.run();
                AssistantInteraction.requireRequestedQuery(draft,requestedQuery,state.getBusinessColumns());
                if(draft.route()!=AssistantPlan.Route.DISPATCH)AssistantInteraction.requireSelected(draft,selected,Map.of());
                if(draft.route()==AssistantPlan.Route.BUSINESS_QUERY) {
                    attemptedRead[0]=true;
                    // 实际只读校验参与有界模型修正；详情匹配多条等错误不能在解析预算结束后才暴露。
                    // 成功事实留在本轮，避免同一草稿重复读取；不会投影卡片或修改派单状态。
                    var result=validated.computeIfAbsent(draft.query(),query->read(user,query));
                    return queryEvidence(result);
                }
                if(draft.analysis()!=null)return validateAnalysis.apply(draft.analysis());
                var facts=draft.dispatch()==null?Map.<String,Object>of():validateDispatch.apply(draft.dispatch());
                AssistantInteraction.requireSelected(draft,selected,facts);return facts;
            },dictionary.context(),diagnostics).withResolvedClarification(selected==null?Map.of():selected.context()).withRequestedQuery(requestedQuery).withAnalysisContext(analysisContext));guard.run();
            if(dictionaries!=null)dictionaries.requireUnchanged(user,dictionary);
        } catch(Exception failure) {
            recordDiagnostic(user,id,requestId,message,"FAILED",null,diagnostics,dictionary,session,guard);
            // 保留失败分类以区分模型契约、业务校验和传输故障；不记录模型原文、请求凭据或外部错误响应。
            log.warn("助手规划失败 conversation={} type={} reason={}",id,failure.getClass().getSimpleName(),
                    failure instanceof ApiException api?com.example.report.operations.SensitiveData.text(api.getMessage()):"非业务异常");
            // 路由本身不可靠时也不能让下一轮省略建单沿用旧意图；明确的只读接口失败则只影响查询上下文。
            state.setUnresolvedRequest(true);
            if(attemptedRead[0])state.setBusinessQueryAfterPreview(true);
            state.setPhase(failurePhase(failure));
            if(attemptedRead[0])state.setBusinessUnresolved(true);
            state.setAssistantRoute("CLARIFY");
            String reason=failure instanceof ApiException api?api.getMessage():"本轮请求未能可靠解析，请明确查询对象、范围或具体操作。";
            // 被拒草稿不写入范围；保留失败原因供下一轮复核和审计定位，首次请求也必须有正确阶段。
            state.setLastReason(com.example.report.operations.SensitiveData.text(reason));
            finish(user,id,requestId,message,reason+" 本轮未应用任何修改。",model,session,guard,emit,started);return Optional.empty();
        }
        state.setParserSource(planner.source());
        recordDiagnostic(user,id,requestId,message,plan.route()==AssistantPlan.Route.CLARIFY?"CLARIFY":"ACCEPTED",plan.route().name(),diagnostics,dictionary,session,guard);
        var accepted=plan;
        session.fenced(()->{guard.run();conversations.logToolCall(id,user.userId(),"assistant_task",Map.of("requestId",requestId,"parserSource",planner.source(),"plan",accepted));return null;});
        if(plan.route()==AssistantPlan.Route.DISPATCH) {state.setAssistantRoute("DISPATCH");state.setAssistantFocus("DISPATCH");return Optional.of(plan);}
        if(plan.route()==AssistantPlan.Route.KNOWLEDGE||plan.route()==AssistantPlan.Route.ANALYSIS)return Optional.of(plan);
        if(attemptedRead[0])state.setBusinessQueryAfterPreview(true);
        boolean previousDispatch="DISPATCH".equals(state.getAssistantFocus());
        state.setAssistantRoute(plan.route().name());
        state.setLastReason(null);
        String reply;
        try {
            reply=switch(plan.route()) {
                case HELP -> "我是业务助手，可以查询报表数据、派单记录、工单进度，解释业务知识并附上依据，也可以做分组、排名和有限多步分析。你可以问“A公司销售报表金额大于五万元的有哪些”“只分析刚才这些，按产品统计金额”“解释A公司销售报表的派单条件”“WO-DEMO-001 到哪个环节了”。工单环节与审批人为演示数据；派单仍需核对清单并点击确认。";
                case CLARIFY -> {
                    state.setUnresolvedRequest(true);
                    if(!previousDispatch)state.setBusinessUnresolved(true);
                    state.setPhase(DialogueState.Phase.CLARIFY);
                    state.setLastReason(com.example.report.operations.SensitiveData.text(plan.clarification()));
                    if(plan.clarificationOptions()!=null){
                        var pending=new AssistantInteraction.PendingClarification(requestId,selected==null?message:selected.pending().message(),plan.clarificationOptions(),user.permissionVersion(),dictionary.fingerprint(),selected==null?List.of():selected.choices());
                        state.setPendingClarification(pending);var payload=clarificationPayload(user,pending,plan.clarification());
                        session.fenced(()->{guard.run();session.save();conversations.logCard(id,user.userId(),"semantic_clarification",payload,null,null);return null;});
                        emit.accept(new AgentEvent("semantic_clarification",payload));
                    }
                    yield plan.clarification()+" 本轮未应用任何修改。";
                }
                case BUSINESS_QUERY -> {
                    // 新查询即使失败也形成业务范围边界，后续“这些”不能隐式引用之前的派单候选。
                    state.setBusinessQueryAfterPreview(true);
                    if(plan.followUp() && (state.getBusinessQuery()==null || state.isBusinessUnresolved())) throw new ApiException(422,"上一查询未完成，请重新明确查询对象和条件");
                    var result=validated.get(plan.query());if(result==null)result=read(user,plan.query());guard.run();
                    // 复核模型可能耗时；即使复用本轮已读取的事实，发布卡片前也必须重新验证当前授权。
                    requireCurrentQueryAccess(user,result.query().reportIds());
                    var interpretation=QueryInterpretation.describe(state.getBusinessQuery(),result.query(),result.columns(),diagnostics.review(),requestId);
                    state.setBusinessPermissionVersion(user.permissionVersion());state.setBusinessQuery(result.query());state.setBusinessUnresolved(false);
                    state.setBusinessQueryRef(requestId);state.setBusinessColumns(result.columns());
                    var snapshot=result.snapshot();
                    state.setBusinessSnapshotId(snapshot==null?null:snapshot.id());
                    state.setBusinessSnapshotObservedAt(snapshot==null?null:result.observedAt());
                    state.setBusinessSnapshotExpiresAt(snapshot==null?null:snapshot.expiresAt());
                    state.setBusinessSnapshotComplete(snapshot!=null&&"COMPLETE".equals(snapshot.completeness()));
                    state.setUnresolvedRequest(false);state.setPhase(DialogueState.Phase.READY);
                    state.setAssistantFocus("BUSINESS_QUERY");
                    var historyReports=new TreeSet<>(state.getBusinessReportIds());historyReports.addAll(result.query().reportIds());state.setBusinessReportIds(List.copyOf(historyReports));
                    var historyCompanies=new TreeSet<>(state.getBusinessCompanyCodes());historyCompanies.addAll(result.query().companyCode()==null?user.companies():Set.of(result.query().companyCode()));state.setBusinessCompanyCodes(List.copyOf(historyCompanies));
                    state.setBusinessReferences(references(result));state.setBusinessTotalCount((long)result.total());
                    var observed=new LinkedHashMap<>(JsonUtil.toMap(JsonUtil.toJson(result)));observed.put("interpretation",interpretation);
                    session.fenced(()->{guard.run();session.save();conversations.logCard(id,user.userId(),"business_query",observed,null,null);return null;});
                    emit.accept(new AgentEvent("business_query",observed));
                    yield summary(result);
                }
                default -> throw new IllegalStateException();
            };
        } catch(Exception failure) {
            state.setBusinessUnresolved(true);
            state.setPhase(failurePhase(failure));
            reply=failure instanceof ApiException api?api.getMessage():"业务查询暂未完成，请稍后重试；本轮没有执行派单或审批。";
            state.setLastReason(com.example.report.operations.SensitiveData.text(reply));
        }
        finish(user,id,requestId,message,reply,model,session,guard,emit,started);return Optional.empty();
    }

    /** 澄清回复在对话租约内检查词典指纹，随后仍按当前身份重新执行完整规划。 */
    public String clarificationMessage(CurrentUser user,DialogueState state,AssistantInteraction.ClarificationReply reply){
        catalog.refreshForValidation();String fingerprint=dictionaries==null?Digests.sha256("[]"):dictionaries.snapshot(user,catalog.visibleReports(user)).fingerprint();
        return AssistantInteraction.clarificationMessage(user,state,reply,fingerprint);
    }
    /** 质量证据写入失败不改变已经通过的业务判定；日志仅保留异常类型，不能回显供应商响应。 */
    private void recordDiagnostic(CurrentUser user,String id,String requestId,String message,String outcome,String route,PlanningDiagnostics diagnostics,SemanticDictionaryService.Snapshot dictionary,DialogueStore.Session session,Runnable guard){
        var payload=new LinkedHashMap<>(diagnostics.snapshot(requestId,outcome,dictionary==null?null:dictionary.fingerprint()));
        payload.put("route",route);payload.put("message",com.example.report.operations.SensitiveData.text(message));payload.put("scope","SEMANTIC_PLANNING");
        payload.put("referenceTime",dictionary==null?null:dictionary.referenceTime().toString());
        try {session.fenced(()->{guard.run();conversations.logSemanticEvidence(id,user.userId(),"assistant_diagnostic",requestId,payload);return null;});}
        catch(RuntimeException failure){log.warn("语义诊断写入未完成 conversation={} type={}",id,failure.getClass().getSimpleName());}
    }
    /** 澄清标签只来自可信目录或稳定值，模型候选中不允许携带任意HTML或自定义名称。 */
    private Map<String,Object> clarificationPayload(CurrentUser user,AssistantInteraction.PendingClarification pending,String question){
        var options=pending.options();var choices=options.values().stream().map(value->{
            String label=value;
            if(options.kind()==ClarificationOptions.Kind.REPORT)label=catalog.requireVisible(user,value).reportName();
            if(options.kind()==ClarificationOptions.Kind.FIELD)label=BusinessFields.forDomain(options.domain(),catalog.requireVisible(user,options.reportId()).fields()).stream().filter(f->f.name().equals(value)).map(f->f.description()+"（"+f.name()+"）").findFirst().orElse(value);
            return Map.of("value",value,"label",label);
        }).toList();return Map.of("clarificationRef",pending.requestId(),"question",question,"kind",options.kind(),"choices",choices);
    }

    /** 复核只使用有界事实；截断明确标注，不改变业务结果或把样本数量当作完整匹配总数。 */
    static Map<String,Object> queryEvidence(BusinessResult result) {
        var evidence=new LinkedHashMap<String,Object>();evidence.put("query",result.query());evidence.put("columns",result.columns());
        evidence.put("totalCount",result.total());evidence.put("displayedCount",result.rows().size());
        var rows=new ArrayList<Map<String,Object>>();int bytes=0;
        for(var row:result.rows()) {
            int size=JsonUtil.toJson(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(rows.size()>=20 || bytes+size>32768)break;
            rows.add(row);bytes+=size;
        }
        evidence.put("rows",rows);evidence.put("evidenceCoversDisplayedRows",rows.size()==result.rows().size());
        evidence.put("allMatchesIncluded",result.query().page()==1 && rows.size()==result.total());return evidence;
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
        for(var row:result.rows()) if(!ids.contains(row.get("reportId")) || !user.companies().contains(row.get("companyCode"))
                || (query.companyCode()!=null && !query.companyCode().equals(row.get("companyCode"))))throw new ApiException(502,"业务接口返回范围外数据");
        if(query.view()==BusinessQuery.View.ELIGIBILITY) {
            if(result.total()!=1 || result.rows().size()!=1)throw new ApiException(502,"资格核验必须返回唯一记录");
            eligibility(result.rows().get(0));
        }
        requireCurrentQueryAccess(user,ids);
        return result;
    }
    /** 读取结束及语义复核结束各检查一次授权；权限变更时丢弃未发布事实，不更新查询引用或输出卡片。 */
    private void requireCurrentQueryAccess(CurrentUser user,List<String> ids) {
        catalog.refreshForValidation();for(String id:ids)catalog.requireVisible(user,id);
        var current=permissions.resolve(user.userId());
        if(!current.tenantId().equals(user.tenantId()) || !current.permissionVersion().equals(user.permissionVersion())) throw ApiException.forbidden("查询期间权限已变化，请重新登录后查询");
    }
    /** 每轮事实与总结先持久化再发出完成文本；失败记录不覆盖派单预览和选择状态。 */
    private void finish(CurrentUser user,String id,String requestId,String message,String reply,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit,long started) {
        session.fenced(()->{guard.run();session.save();session.record(requestId,message,null,"ASSISTANT_"+session.state().getAssistantRoute(),session.state().getLastReason(),model,(System.nanoTime()-started)/1_000_000);
            conversations.logAssistant(id,user.userId(),reply,model,null,(System.nanoTime()-started)/1_000_000);return null;});
        emit.accept(new AgentEvent(AgentEvent.TEXT,Map.of("delta",reply)));
    }
    /** 统一规划与派单执行使用同一失败分类；协议澄清、业务拒绝和非业务异常不伪装成就绪。 */
    private static DialogueState.Phase failurePhase(Exception failure) {
        return failure instanceof ApiException api && api.getCode()==422?DialogueState.Phase.CLARIFY
                :failure instanceof ApiException?DialogueState.Phase.REJECTED:DialogueState.Phase.FAILED;
    }
    private static List<Map<String,String>> references(BusinessResult result) {
        List<Map<String,String>> references=new ArrayList<>();
        for(int i=0;i<result.rows().size();i++) {
            var row=result.rows().get(i);var ref=new LinkedHashMap<String,String>();ref.put("displayIndex",String.valueOf((result.query().page()-1)*result.query().size()+i+1));
            for(String key:List.of("reportId","recordId","companyCode","docNo","planId","requestId","orderId"))if(row.get(key)!=null)ref.put(key,row.get(key).toString());references.add(ref);
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
        if(result.query().view()==BusinessQuery.View.ELIGIBILITY) {
            var row=result.rows().get(0);var check=eligibility(row);
            String text="单据 "+Objects.toString(row.get("docNo"),row.get("recordId").toString())
                    +(check.eligible()?" 符合当前派单条件。":" 当前不符合派单条件。")+check.reason();
            if(check.ruleName()!=null)text+="适用规则："+check.ruleName()+(check.ruleDescription()==null?"":"（"+check.ruleDescription()+"）")+"。";
            if(!check.checkedFields().isEmpty())text+="核验依据："+check.checkedFields().stream().map(field->{
                String name=result.columns().stream().filter(c->c.name().equals(field.name())).map(c->Objects.toString(c.description(),c.name()).split("[，；;]",2)[0]).findFirst().orElse(field.name());
                return name+"="+Objects.toString(field.value(),"空值");
            }).collect(java.util.stream.Collectors.joining("；"))+"。";
            return text+"本次为只读核验，实际派单仍需核对清单并确认。";
        }
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
    /** 资格结论必须带业务服务证据；缺失字段不能被JSON默认布尔值伪装成不符合条件。 */
    private static DispatchEligibility eligibility(Map<String,Object> row) {
        try {
            var result=JsonUtil.MAPPER.convertValue(row.get("eligibility"),DispatchEligibility.class);
            if(result==null)throw new IllegalArgumentException();
            return result;
        } catch(RuntimeException invalid) {throw new ApiException(502,"业务服务未返回完整的派单资格核验依据");}
    }
    private static String counts(Map<String,Integer> values){return values.isEmpty()?"暂无记录":values.entrySet().stream().map(e->e.getKey()+" "+e.getValue()+" 条").collect(java.util.stream.Collectors.joining("；"));}
}
