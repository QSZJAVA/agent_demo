package com.example.report.semantic;

import com.example.report.agent.*;
import com.example.report.assistant.*;
import com.example.report.catalog.*;
import com.example.report.common.*;
import com.example.report.config.*;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.*;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.entity.*;
import com.example.report.operations.SensitiveData;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.Candidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static com.example.report.semantic.SemanticIntent.Target.*;

/**
 * 统一对话租约与任务编排：完整计划一次确定动作和目标，执行层只做确定性求值，不重复解释用户原文。
 * 查询身份可以继续用于派单准备，但必须重新核验；模型不能确认派单，连接、租约和版本守卫覆盖整轮写入。
 */
@Slf4j
@Service
public class SemanticConversationService {
    private final IntentCodec codec;
    private final DialogueStore store;
    private final SemanticPlanner planner;
    private final ConversationService conversations;
    private final PreviewService previews;
    private final PlanService plans;
    private final PlanRepository planRepository;
    private final ReportCatalogService catalog;
    private final ResourceQuotaService quotas;
    private final AgentProperties props;
    private final com.example.report.assistant.BusinessAssistantService assistant;
    public SemanticConversationService(IntentCodec codec, DialogueStore store, SemanticPlanner planner,
            ConversationService conversations, PreviewService previews, PlanService plans, PlanRepository planRepository,
            ReportCatalogService catalog, ResourceQuotaService quotas, AgentProperties props,com.example.report.assistant.BusinessAssistantService assistant) {
        this.codec=codec;this.store=store;this.planner=planner;this.conversations=conversations;
        this.previews=previews;this.plans=plans;this.planRepository=planRepository;this.catalog=catalog;this.quotas=quotas;this.props=props;
        this.assistant=assistant;
        if (!"active".equals(props.getSemantic().getMode())) throw new IllegalArgumentException("最终演示版仅支持 agent.semantic.mode=active");
    }
    public boolean enabled() { return "active".equals(props.getSemantic().getMode()); }
    public String modelName(String fallback) {
        String configured=props.getSemantic().getModel();return configured==null || configured.isBlank()?fallback:configured;
    }
    /**
     * 建立用户所属会话的 SSE 处理流；对话租约和并发配额覆盖整轮处理，结束前释放。客户端断开后按守卫停止写入，已保存事实可刷新恢复。
     */
    public Flux<ServerSentEvent<Object>> chat(CurrentUser user,String conversationId,String message,
            String uiPreviewId,List<RecordKey> excludedRecords,String model) {
        var conversation=conversationId==null || conversationId.isBlank() ? conversations.create(user,model) : conversations.getOwned(user,conversationId);
        String id=conversation.getId(), requestId=JsonUtil.newId();
        previews.requireConversationReadable(user,id);
        Flux<AgentEvent> events=Flux.<AgentEvent>create(sink -> {
            try (var permit=quotas.acquire(user,"chat",List.of()); var session=store.acquire(user,id)) {
                sink.next(new AgentEvent(AgentEvent.CONVERSATION,Map.of("conversationId",id,"requestId",requestId,"title",Objects.toString(conversation.getTitle(),""))));
                Runnable guard=() -> { session.check(); ResourceQuotaService.check(permit); if (sink.isCancelled()) throw new ApiException(409,"对话连接已关闭"); };
                Consumer<AgentEvent> emit=event -> { guard.run(); sink.next(event); };
                turn(user,id,requestId,message,uiPreviewId,excludedRecords,model,session,guard,emit);
            } catch (Exception failure) {
                log.warn("语义对话未完成 conversation={} type={}",id,failure.getClass().getSimpleName());
                sink.next(new AgentEvent(AgentEvent.ERROR,Map.of("message",friendly(failure))));
            }
            // A completed stream permits another turn; release the dialogue and quota leases first.
            sink.next(new AgentEvent(AgentEvent.DONE,Map.of())); sink.complete();
        }).subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofSeconds(store.timeoutSeconds()+5))
                .onErrorResume(e -> Flux.just(new AgentEvent(AgentEvent.ERROR,Map.of("message","本次处理超时，请刷新会话查看已保存的结果后重试")),new AgentEvent(AgentEvent.DONE,Map.of())));
        return events.index().map(e -> ServerSentEvent.builder((Object)SensitiveData.typed(e.getT2().data()))
                .id(requestId+":"+e.getT1()).event(e.getT2().type()).build());
    }
    /**
     * 本轮流程：恢复权威状态、解析并验证意图、合并期望范围、执行确定性业务、保存结果证据。失败只更新澄清/拒绝/失败阶段，不能把失败范围伪装成成功范围。
     */
    private void turn(CurrentUser user,String id,String requestId,String message,
            String uiPreviewId,List<RecordKey> uiExcludes,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit) {
        long started=System.nanoTime();
        var state=session.state(); SemanticIntent intent=null; String reply;
        state.setParserSource(null);
        conversations.logUser(id,user.userId(),message);
        try {
            hydrate(user,id,state);
            // 统一计划直接交接完整动作和对象；只读分支结束本轮，不占用预览版本或修改候选选择。
            var task=assistant.handle(user,id,requestId,message,model,session,guard,emit,currentSelection(user,state),
                    directive->validateDispatchDraft(user,state,directive,message,uiPreviewId,uiExcludes));
            if(task.isEmpty())return;
            var directive=task.get();intent=directive.intent();
            codec.validate(intent,message);AssistantReferences.validate(message,state,directive);guard.run();
            state.setAttemptedAt(LocalDateTime.now());
            // 新消息使此前排队的界面查询失效；即使新意图被拒绝，也不能让迟到任务覆盖当前状态。
            long previewVersion=previews.beginRequest(id);
            catalog.refreshForValidation();
            state.setPendingIntent(intent);
            if(directive.source()==DispatchDirective.Source.QUERY_ROWS || directive.source()==DispatchDirective.Source.QUERY_ALL) {
                reply=queryTargets(user,id,requestId,previewVersion,directive,state,session,guard,emit);
            } else {
                boolean fresh=directive.source()==DispatchDirective.Source.EXPLICIT_SCOPE;
                if(fresh) {
                    // 明确新范围从初始授权边界计算，不继承不相关话题的公司、报表或排除集合。
                    var draft=new DialogueState();planner.merge(user,draft,intent);planner.validate(user,draft);
                    state.setDesired(draft.getDesired());state.setUnresolvedCompany(false);state.setUnresolvedReports(false);
                } else planner.merge(user,state,intent);
                session.save();planner.requireAction(state,intent);
                reply=switch(intent.action()) {
                    case CANCEL_PLAN -> cancel(user,id,state,session,guard);
                    case SHOW_RESULT -> result(user,id,state,emit);
                    case PREVIEW, PREPARE_DISPATCH, EXPLAIN_RULES -> query(user,id,requestId,previewVersion,intent,state,session,
                            fresh?null:uiPreviewId,fresh?null:uiExcludes,guard,emit,message,fresh);
                    default -> throw new ApiException(422,"请明确本次操作");
                };
            }
            state.setLastReason(null);state.setUnresolvedRequest(false);
        } catch (Exception failure) {
            if (intent==null) {
                // 未解析的请求整轮不提交；阻止含糊建单，保留已确定范围以便下一轮明确纠正记录条件。
                state.setUnresolvedRequest(true);
            }
            state.setPhase(failure instanceof ApiException api && api.getCode()==422 ? DialogueState.Phase.CLARIFY
                    : failure instanceof ApiException ? DialogueState.Phase.REJECTED : DialogueState.Phase.FAILED);
            reply=friendly(failure); state.setLastReason(reply);
            // 只记录契约校验原因以便复现，不记录模型原始输出或认证凭据。
            if(failure instanceof IntentCodec.InvalidOutput invalid)log.warn("语义草稿拒绝 conversation={} reason={}",id,SensitiveData.text(invalid.reason()));
            if (!(failure instanceof ApiException)) log.warn("语义处理失败 conversation={} type={}",id,failure.getClass().getSimpleName());
        }
        var recent=new ArrayList<>(state.getRecentUserMessages()); recent.add(SensitiveData.text(message));
        state.setRecentUserMessages(List.copyOf(recent.subList(Math.max(0,recent.size()-4),recent.size())));
        session.save();
        session.record(requestId,message,intent,state.getPhase().name(),state.getLastReason(),
                model,(System.nanoTime()-started)/1_000_000);
        conversations.logAssistant(id,user.userId(),reply,null,null,(System.nanoTime()-started)/1_000_000);
        emit.accept(new AgentEvent(AgentEvent.TEXT,Map.of("delta",reply)));
        emit.accept(new AgentEvent("selection",selection(state)));
    }
    /**
     * 仅在期望范围与已生效范围一致且预览仍有效时复用事实。记录排除绑定当前预览，重新查询后需重新对齐选择；生成清单仍不代表执行派单。
     */
    private String query(CurrentUser user,String id,String requestId,long previewVersion,SemanticIntent intent,DialogueState state,
            DialogueStore.Session session,String uiPreviewId,List<RecordKey> uiExcludes,Runnable guard,Consumer<AgentEvent> emit,String message,boolean fresh) {
        planner.validate(user,state);
        PreviewSnapshot snapshot=null;
        boolean afterBusinessQuery=state.isBusinessQueryAfterPreview();
        boolean sameScope=!fresh && Objects.equals(state.getDesired(),state.getEffective());
        List<RecordTarget> exactTargets=List.of();String targetSourceRef=null;
        if (sameScope && state.getPreviewId()!=null) {
            var saved=previews.getOwned(user,state.getPreviewId());
            if(!intent.changes(COMPANY) && !intent.changes(REPORTS)) {exactTargets=saved.targets();targetSourceRef=saved.targetSourceRef();}
            if (DispatchPreview.ACTIVE.equals(saved.preview().getStatus())) snapshot=saved;
        }
        boolean keepsReports=intent.reportConstraints().stream().anyMatch(c->c.role()==SemanticIntent.ReportRole.UNCHANGED || c.role()==SemanticIntent.ReportRole.UNCHANGED_OTHERS);
        boolean onlySelection=(intent.changes(RECORDS) || keepsReports) && !intent.changes(COMPANY) && !intent.changes(REPORTS);
        if(keepsReports && snapshot==null && state.getPreviewId()!=null)
            throw new ApiException(422,"原预览已变化，无法保持原选择，请先重新查询再指定选择");
        String previousPreviewId=state.getPreviewId();
        boolean refreshed=false;
        if (snapshot==null || (intent.action()==SemanticIntent.Action.PREVIEW && !onlySelection)) {
            state.setPhase(DialogueState.Phase.QUERYING); session.save();
            // Stream factual progress; the final count only comes from the completed snapshot.
            emit.accept(new AgentEvent(AgentEvent.TEXT,Map.of("delta","正在查询可派单记录。\n")));
            var scope=state.getDesired();
            var command=new PreviewCommand("PREVIEW", "semantic", null, scope.allReports()?List.of():scope.reportIds(), new PreviewCommand.Filters(scope.companyCode()), "replace");
            var outcome=exactTargets.isEmpty()
                    ?previews.preview(user,id,command,n -> guard.run(),pid -> session.fenced(() -> { guard.run(); return null; }),previewVersion)
                    :previews.previewRecords(user,id,exactTargets,targetSourceRef,n -> guard.run(),pid -> session.fenced(() -> { guard.run(); return null; }),previewVersion);
            if (outcome.status()!=PreviewOutcome.Status.OK) throw new ApiException(422,"当前范围没有可用报表，请重新指定报表名称");
            snapshot=outcome.snapshot();
            state.setPreviewId(snapshot.preview().getId()); state.setEffective(scope); state.setPlanId(null);
            state.setBusinessQueryAfterPreview(false);
            if(fresh) {state.setExcludedRecords(List.of());state.setUnresolvedRecords(false);}
            SelectionReferences.clear(state);
            refreshed=true;
            session.save();
            var payload=PreviewPayload.of(snapshot,catalog);
            conversations.logCard(id,user.userId(),"preview",payload,payload.previewId(),null);
            emit.accept(new AgentEvent(AgentEvent.PREVIEW,payload));
        }
        boolean uiMatches=Objects.equals(uiPreviewId,state.getPreviewId());
        // Migrate explicit UI selection only after validating its original source and the refreshed snapshot.
        if (refreshed && sameScope && previousPreviewId!=null && Objects.equals(uiPreviewId,previousPreviewId) && uiExcludes!=null) {
            if (!uiExcludes.isEmpty()) SelectionResolver.validate(rows(user,previews.getOwned(user,previousPreviewId)),uiExcludes);
            state.setExcludedRecords(List.copyOf(uiExcludes));
            uiMatches=true;
        }
        // 只读查询形成了新的业务边界；用户随后明确取得不同报表的候选时，移出范围的旧排除键退出新选择集合。
        // 连续派单中的普通范围切换仍保留原恢复保护；按快照报表而非有记录的报表过滤，当前报表失效记录仍须核对。
        if(refreshed && afterBusinessQuery && !sameScope && intent.changes(REPORTS)) {
            Set<String> activeReports=new HashSet<>(snapshot.reportIds());
            state.setExcludedRecords(state.getExcludedRecords().stream().filter(key->activeReports.contains(key.reportId())).toList());
        }
        // 有记录操作时先按新快照求值并在下方校验最终集合，允许RESTORE_ALL/REPLACE_EXCLUSIONS修复旧失配。
        if (refreshed && !state.getExcludedRecords().isEmpty() && !intent.changes(RECORDS)) {
            try { SelectionResolver.validate(rows(user,snapshot),state.getExcludedRecords()); }
            catch (ApiException unavailable) {
                state.setUnresolvedRecords(true);
                throw new ApiException(422,"预览已更新，原排除记录不能完整恢复；请明确恢复全部记录或重新指定排除项后生成清单");
            }
        }
        if (uiPreviewId!=null && !uiMatches && uiExcludes!=null && !uiExcludes.isEmpty())
            throw new ApiException(422,"查询范围已变化，旧预览的勾选未应用；请在新预览上重新选择后派单");
        // 明确替换/恢复全部报表并重新取得成功快照，已建立新的查询基线；不继续携带旧失败选择的歧义。
        // 先完整验证保留下来的排除键，不能借此放过来源范围变化导致的选择失配，也不适用于省略建单。
        if(refreshed && intent.action()==SemanticIntent.Action.PREVIEW && !intent.changes(RECORDS)
                && intent.changesFor(REPORTS).stream().anyMatch(c->Set.of(SemanticIntent.Operation.REPLACE,SemanticIntent.Operation.CLEAR).contains(c.operation())))
            state.setUnresolvedRecords(false);
        if (state.isUnresolvedRecords() && !intent.changes(RECORDS)
                && (!uiMatches || uiExcludes==null || uiExcludes.equals(state.getExcludedRecords())))
            throw new ApiException(422,"上次排除记录尚未确定，请说明准确单据号、明确恢复全部记录，或在表格中重新选择");
        if (intent.changes(RECORDS) || (uiMatches && uiExcludes!=null && !uiExcludes.isEmpty())) {
            state.setUnresolvedRecords(true);
            var rows=rows(user,snapshot);
            List<RecordKey> selected=uiMatches && uiExcludes!=null ? List.copyOf(uiExcludes) : state.getExcludedRecords();
            // 报表限定仅参与当前快照的记录定位；整轮成功后才提交选择，不能在中途丢失限定或部分排除。
            for (var change:intent.scopeChanges()) if (change.target()==RECORDS)
                selected=SelectionResolver.apply(rows,selected,change,catalog,user,state,message);
            SelectionResolver.validate(rows,selected);
            if(intent.changes(RECORDS)) {
                var changes=intent.scopeChanges().stream().filter(c->c.target()==RECORDS).toList();
                captureSelectionReferences(user,state,rows,changes.get(changes.size()-1));
                state.setLastSuccessfulSelection(changes);
            }
            state.setExcludedRecords(selected);
            state.setUnresolvedRecords(false);
        } else if (uiMatches && uiExcludes!=null) {
            state.setExcludedRecords(List.copyOf(uiExcludes));state.setUnresolvedRecords(false);
        }
        state.setPhase(DialogueState.Phase.READY);
        if (intent.action()==SemanticIntent.Action.EXPLAIN_RULES) {
            var rules=PreviewPayload.of(snapshot,catalog).ruleDescriptions();
            return rules.isEmpty()?"当前预览没有命中规则说明。":"当前预览使用的规则："+String.join("；",rules.values());
        }
        if (intent.action()==SemanticIntent.Action.PREPARE_DISPATCH) {
            return prepare(user,id,requestId,state,snapshot,session,guard,emit);
        }
        return "当前预览共 "+snapshot.preview().getTotalCount()+" 条，已排除 "+state.getExcludedRecords().size()+" 条。";
    }
    private List<Candidate> rows(CurrentUser user,PreviewSnapshot snapshot) {
        if (snapshot.preview().getTotalCount()>props.getPreview().getMaxItems()) throw new ApiException(422,"记录较多，请先缩小报表或公司范围后再排除记录");
        List<Candidate> rows=new ArrayList<>();
        for(int page=1;rows.size()<snapshot.preview().getTotalCount();page++) {
            var batch=previews.pageOwned(user,snapshot.preview().getId(),page,100); if(batch.isEmpty()) break; rows.addAll(batch);
        }
        // “全部匹配”必须基于完整且不重复的快照；分页缺失不能被当成只匹配了少数记录。
        if(rows.size()!=snapshot.preview().getTotalCount() || rows.stream().map(Candidate::key).distinct().count()!=rows.size())
            throw new ApiException(422,"预览记录不完整，未应用本轮选择，请重新查询");
        return rows;
    }
    /** 从本次成功定位的真实候选中提取中性引用，限制数量和体积；不让旧动作文字污染下一轮解析。 */
    private void captureSelectionReferences(CurrentUser user,DialogueState state,List<Candidate> rows,SemanticIntent.ScopeChange change) {
        boolean all=change.operation()==SemanticIntent.Operation.RESTORE_ALL;
        var target=new SemanticIntent.ScopeChange(RECORDS,SemanticIntent.Operation.EXCLUDE,all?List.of():change.mentions(),change.evidence(),change.reportMentions(),
                all?SemanticIntent.SelectorKind.ALL:change.selectorKind(),all?SemanticIntent.Quantifier.ALL:change.quantifier(),change.conditions());
        Set<RecordKey> keys=new HashSet<>(SelectionResolver.apply(rows,List.of(),target,catalog,user,state));
        SelectionReferences.capture(state,rows,keys);
    }
    /**
     * 对完整任务进行无写入预检，返回可复核的实际对象和选择结果；失败不能让模型悄悄换对象或丢条件。
     * 新范围只在草稿求值，查询引用只解析身份；真正预览仍会重新读取当前资格并绑定执行版本。
     */
    private Map<String,Object> validateDispatchDraft(CurrentUser user,DialogueState state,DispatchDirective directive,String message,
            String uiPreviewId,List<RecordKey> uiExcludes) {
        codec.validate(directive.intent(),message);AssistantReferences.validate(message,state,directive);
        if(directive.source()==DispatchDirective.Source.QUERY_ROWS || directive.source()==DispatchDirective.Source.QUERY_ALL) {
            var targets=resolveTargets(user,state,directive);
            return Map.of("sourceRef",directive.sourceRef(),"targetRecords",targets,"targetCount",targets.size(),
                    "qualification","PREPARATION_WILL_RECHECK_CURRENT_RULES_AND_STATUS");
        }
        if(directive.source()==DispatchDirective.Source.PLAN) {
            var saved=plans.inspectOwned(user,state.getPlanId());
            return Map.of("sourceRef",directive.sourceRef(),"planStatus",saved.getStatus(),"itemCount",saved.getItemCount());
        }
        boolean fresh=directive.source()==DispatchDirective.Source.EXPLICIT_SCOPE;
        // 旧页面的排除项在任何刷新/范围变更之前拒绝；明确新范围会丢弃旧UI选择，不受此限制。
        if(!fresh && uiPreviewId!=null && !Objects.equals(uiPreviewId,state.getPreviewId()) && uiExcludes!=null && !uiExcludes.isEmpty())
            throw new ApiException(422,"查询范围已变化，旧预览的勾选未应用；请在当前预览上重新选择后派单");
        var draft=fresh?new DialogueState():JsonUtil.MAPPER.convertValue(state,DialogueState.class);
        var intent=directive.intent();planner.merge(user,draft,intent);planner.validate(user,draft);
        if(fresh || state.getPreviewId()==null || !Objects.equals(draft.getDesired(),state.getEffective())
                || intent.changes(COMPANY) || intent.changes(REPORTS))
            return Map.of("scope",draft.getDesired(),"requiresCandidateRead",true);
        var snapshot=previews.inspectOwned(user,state.getPreviewId());
        if(!DispatchPreview.ACTIVE.equals(snapshot.preview().getStatus()) || snapshot.preview().getTotalCount()>props.getPreview().getMaxItems())
            return Map.of("scope",draft.getDesired(),"previewStatus",snapshot.preview().getStatus(),"requiresCandidateRead",true);
        var candidates=rows(user,snapshot);
        boolean uiMatches=Objects.equals(uiPreviewId,state.getPreviewId()) && uiExcludes!=null;
        if(state.isUnresolvedRecords() && !intent.changes(RECORDS)
                && (!uiMatches || uiExcludes.equals(state.getExcludedRecords())))
            throw new ApiException(422,"上次记录选择尚未确定，请明确目标、恢复全部或重新选择后再生成清单");
        var before=uiMatches?List.copyOf(uiExcludes):state.getExcludedRecords();
        var selected=before;
        for(var change:intent.scopeChanges())if(change.target()==RECORDS)
            selected=SelectionResolver.apply(candidates,selected,change,catalog,user,draft,message);
        // 先求值完整的恢复/替换操作，再检查最终集合；旧失效键不能阻止用户显式恢复全部。
        // 普通增量操作仍须保留并校验旧键，不能悄悄丢弃未解决的排除项。
        SelectionResolver.validate(candidates,selected);draft.setExcludedRecords(selected);
        return Map.of("scope",draft.getDesired(),"sourceRef",directive.sourceRef(),
                "selectionAfter",SelectionReferences.describeSelection(draft,candidates),"excludedCount",selected.size());
    }

    /** 当前身份必须继续拥有查询目标；引用的权限版本变化不能被一次新派单请求绕过。 */
    private List<RecordTarget> resolveTargets(CurrentUser user,DialogueState state,DispatchDirective directive) {
        if(!Objects.equals(user.permissionVersion(),state.getBusinessPermissionVersion()))
            throw ApiException.forbidden("查询后权限已变化，请在当前授权范围重新查询");
        var targets=AssistantReferences.resolveQuery(state,directive);
        for(var target:targets) {
            catalog.requireDispatchable(user,target.key().reportId());
            if(!user.companies().contains(target.companyCode()))throw ApiException.forbidden("目标公司不存在或无权访问");
        }
        return targets;
    }

    /** 精确查询对象自动重新核验并生成候选；只有本轮请求准备时才继续生成PENDING清单。 */
    private String queryTargets(CurrentUser user,String id,String requestId,long previewVersion,DispatchDirective directive,DialogueState state,
            DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit) {
        var targets=resolveTargets(user,state,directive);
        conversations.logToolCall(id,user.userId(),"dispatch_targets",Map.of("requestId",requestId,"sourceRef",directive.sourceRef(),"targets",targets));
        emit.accept(new AgentEvent(AgentEvent.TEXT,Map.of("delta","正在核验指定记录的当前状态和派单规则。\n")));
        var outcome=previews.previewRecords(user,id,targets,directive.sourceRef(),n->guard.run(),
                pid->session.fenced(()->{guard.run();return null;}),previewVersion);
        if(outcome.status()!=PreviewOutcome.Status.OK)throw new ApiException(422,"指定目标的报表当前不可派单，请重新查询核对");
        var snapshot=outcome.snapshot();
        var companyCodes=targets.stream().map(RecordTarget::companyCode).distinct().toList();
        var scope=new DialogueState.Scope(companyCodes.size()==1?companyCodes.get(0):null,false,snapshot.reportIds());
        state.setDesired(scope);state.setEffective(scope);state.setPreviewId(snapshot.preview().getId());state.setPlanId(null);
        state.setExcludedRecords(List.of());state.setUnresolvedCompany(false);state.setUnresolvedReports(false);state.setUnresolvedRecords(false);
        state.setBusinessQueryAfterPreview(false);state.setPhase(DialogueState.Phase.READY);SelectionReferences.clear(state);
        SelectionReferences.capture(state,snapshot.candidates(),targets.stream().map(RecordTarget::key).collect(java.util.stream.Collectors.toSet()));
        session.save();
        var payload=PreviewPayload.of(snapshot,catalog);conversations.logCard(id,user.userId(),"preview",payload,payload.previewId(),null);
        emit.accept(new AgentEvent(AgentEvent.PREVIEW,payload));
        if(directive.intent().action()==SemanticIntent.Action.PREPARE_DISPATCH)return prepare(user,id,requestId,state,snapshot,session,guard,emit);
        return "已核验指定的 "+targets.size()+" 条记录，当前均符合派单条件，可在候选卡片中核对。";
    }

    /** 有效快照生成待确认清单；沿用请求幂等键与会话租约，不调用确认或执行接口。 */
    private String prepare(CurrentUser user,String id,String requestId,DialogueState state,PreviewSnapshot snapshot,
            DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit) {
        if(snapshot.preview().getTotalCount()==0)return "当前查询结果为 0 条，没有可生成清单的记录。";
        var plan=session.fenced(()->{guard.run();return plans.create(user,id,state.getPreviewId(),state.getExcludedRecords(),"semantic:"+requestId);});
        state.setPlanId(plan.plan().getId());state.setPhase(DialogueState.Phase.PLAN_READY);
        var payload=PlanPayload.of(plan);conversations.logCard(id,user.userId(),"plan",payload,state.getPreviewId(),state.getPlanId());
        emit.accept(new AgentEvent(AgentEvent.PLAN,payload));
        return "已生成待确认清单，共 "+payload.count()+" 条。请核对卡片并点击“确认派单”后执行。";
    }

    /** 候选作为有来源的独立对象供规划；当前话题变化不删除它，也不让它自动替代刚查询的对象。 */
    private Map<String,Object> currentSelection(CurrentUser user,DialogueState state) {
        if(state.getPreviewId()==null)return Map.of();
        var snapshot=previews.inspectOwned(user,state.getPreviewId());
        var context=new LinkedHashMap<String,Object>();context.put("sourceRef",AssistantReferences.previewRef(state));
        context.put("status",snapshot.preview().getStatus());context.put("totalCount",snapshot.preview().getTotalCount());
        context.put("scopeMatches",Objects.equals(state.getDesired(),state.getEffective()));
        context.put("exactTargets",snapshot.targets());
        if(DispatchPreview.ACTIVE.equals(snapshot.preview().getStatus()) && snapshot.preview().getTotalCount()<=props.getPreview().getMaxItems())
            context.putAll(SelectionReferences.describeSelection(state,rows(user,snapshot)));
        else {context.put("selectedRows",List.of());context.put("complete",false);}
        return context;
    }
    private void hydrate(CurrentUser user,String id,DialogueState state) {
        // UI生成的清单也成为有来源对象；规划之后不再查询“最新清单”替换已经绑定的目标。
        var conversationPlans=planRepository.byConversation(id);
        conversationPlans.stream().map(DispatchPlan::getCreatedAt).max(Comparator.naturalOrder()).ifPresent(latestTime->{
            var latest=conversationPlans.stream().filter(p->p.getCreatedAt().equals(latestTime)).toList();
            var pending=latest.stream().filter(p->DispatchPlan.PENDING.equals(p.getStatus())).toList();
            var current=latest.stream().filter(p->!Set.of(StateReason.NEW_PLAN,StateReason.NEW_PREVIEW)
                    .contains(Objects.toString(p.getStatusReason(),""))).toList();
            // 同刻的新清单即使已在UI取消，也不能回绑被它取代的旧清单；没有唯一依据时留空交由规划澄清。
            String bound=pending.size()==1?pending.get(0).getId():latest.size()==1?latest.get(0).getId()
                    :current.size()==1?current.get(0).getId():null;
            state.setPlanId(bound);
        });
        previews.latest(user,id).ifPresent(latest -> {
            boolean initial=state.getAttemptedAt()==null;
            boolean explicitUi=Set.of("selection","api").contains(Objects.toString(latest.getSource(),""))
                    && state.getAttemptedAt()!=null && latest.getCreatedAt().isAfter(state.getAttemptedAt());
            if (!Objects.equals(latest.getId(),state.getPreviewId()) && (initial || explicitUi)) {
                var snapshot=previews.inspectOwned(user,latest.getId());
                if (!DispatchPreview.ACTIVE.equals(snapshot.preview().getStatus())) return;
                var query=snapshot.query(); Object filters=query.get("filters");
                String company=filters instanceof Map<?,?> m ? (String)m.get("companyCode") : null;
                var scope=new DialogueState.Scope(company,Boolean.TRUE.equals(query.get("allReports")),snapshot.reportIds());
                state.setDesired(scope);state.setEffective(scope);state.setPreviewId(latest.getId());state.setExcludedRecords(List.of());
                // 新界面预览已经重新展示候选，恢复其业务焦点；旧查询不能继续阻断用户在该快照上的准备。
                state.setBusinessQueryAfterPreview(false);state.setAssistantFocus("DISPATCH");
                SelectionReferences.clear(state);state.setUnresolvedRecords(false);
                state.setUnresolvedReports(false);state.setUnresolvedCompany(false);state.setPhase(DialogueState.Phase.READY);
            }
        });
    }
    /** 清单必须与已验证的当前引用一致，不能在执行时重新挑选另一份最新清单。 */
    private PlanSnapshot currentPlan(CurrentUser user,DialogueState state) {
        if(state.getPlanId()==null)throw new ApiException(422,"当前会话没有可引用的派单清单");
        return plans.getOwned(user,state.getPlanId());
    }
    private String cancel(CurrentUser user,String id,DialogueState state,DialogueStore.Session session,Runnable guard) {
        var snapshot=currentPlan(user,state);
        var plan=session.fenced(() -> { guard.run(); return plans.cancel(user,snapshot.plan().getId()); });
        state.setPhase(DialogueState.Phase.READY);
        return "当前清单状态："+statusName(plan.getStatus())+"。";
    }
    private String result(CurrentUser user,String id,DialogueState state,Consumer<AgentEvent> emit) {
        var snapshot=currentPlan(user,state); var plan=snapshot.plan();
        emit.accept(new AgentEvent(AgentEvent.PLAN,PlanPayload.of(snapshot)));
        return "当前清单状态："+statusName(plan.getStatus())+"；共 "+plan.getItemCount()+" 条，成功 "+Objects.toString(plan.getSuccessCount(),"0")+" 条，失败 "+Objects.toString(plan.getFailedCount(),"0")+" 条。";
    }
    private static String statusName(String status) {
        return switch(status) { case "PENDING" -> "待确认";case "CANCELLED" -> "已取消";case "EXPIRED" -> "已失效";
            case "EXECUTING" -> "执行中";case "EXECUTED" -> "已完成";case "REVIEW_REQUIRED" -> "待核对";default -> "请查看卡片"; };
    }
    public Map<String,Object> selection(CurrentUser user,String id) {
        previews.requireConversationReadable(user,id);
        return selection(store.read(user,id));
    }
    /**
     * 持久化界面手动选择；租约串行化当前会话，旧选择作为比较条件，避免迟到请求覆盖另一页面的修改。
     * 仅允许当前有效、归属一致的预览；重复提交相同最终集合可重放，不调用模型、不建单、不执行派单。
     */
    public Map<String,Object> updateSelection(CurrentUser user,String id,String previewId,List<RecordKey> expected,List<RecordKey> next) {
        if(previewId==null || previewId.isBlank() || expected==null || next==null || expected.size()>props.getPreview().getMaxItems() || next.size()>props.getPreview().getMaxItems())
            throw new ApiException(422,"选择参数不完整或超过上限");
        if(new HashSet<>(expected).size()!=expected.size() || new HashSet<>(next).size()!=next.size())
            throw new ApiException(422,"选择记录不能重复");
        previews.requireConversationReadable(user,id);
        try(var session=store.acquire(user,id)) {
            var state=session.state();hydrate(user,id,state);
            if(!Objects.equals(previewId,state.getPreviewId()))throw new ApiException(409,"预览已变化，请重新读取选择");
            var snapshot=previews.getOwned(user,previewId);
            if(!Objects.equals(id,snapshot.preview().getConversationId()) || !DispatchPreview.ACTIVE.equals(snapshot.preview().getStatus()))
                throw new ApiException(409,"预览已失效，请重新查询");
            var records=rows(user,snapshot);SelectionResolver.validate(records,expected);SelectionResolver.validate(records,next);
            var current=new HashSet<>(state.getExcludedRecords());var desired=new HashSet<>(next);
            if(!current.equals(new HashSet<>(expected)) && !current.equals(desired))
                throw new ApiException(409,"选择已在另一页面更新，请重新读取后再操作");
            if(!current.equals(desired))SelectionReferences.clear(state);
            state.setExcludedRecords(List.copyOf(next));state.setUnresolvedRecords(false);
            if(!state.isUnresolvedCompany() && !state.isUnresolvedReports()) {
                state.setUnresolvedRequest(false);state.setPhase(DialogueState.Phase.READY);state.setLastReason(null);
            }
            session.save();return selection(state);
        }
    }
    private static Map<String,Object> selection(DialogueState state) {
        Map<String,Object> value=new LinkedHashMap<>(); value.put("previewId",state.getPreviewId()); value.put("excludedRecords",state.getExcludedRecords()); value.put("phase",state.getPhase()); return value;
    }
    private static String friendly(Exception e) {
        return e instanceof ApiException ? SensitiveData.text(e.getMessage()) : "本次处理暂时失败，请稍后重试；尚未确认的清单不会执行派单。";
    }
}
