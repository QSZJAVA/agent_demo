package com.example.report.semantic;

import com.example.report.agent.*;
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
 * 统一对话租约与编排：先处理通用只读查询，再由派单分支解析意图、更新权威状态并发送事实卡片。
 * 模型不能确认派单或审批；SSE断开、租约过期或删除会话时停止旧轮次写入，两类查询上下文独立保存。
 */
@Slf4j
@Service
public class SemanticConversationService {
    private final IntentParser parser;
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
    public SemanticConversationService(IntentParser parser, IntentCodec codec, DialogueStore store, SemanticPlanner planner,
            ConversationService conversations, PreviewService previews, PlanService plans, PlanRepository planRepository,
            ReportCatalogService catalog, ResourceQuotaService quotas, AgentProperties props,com.example.report.assistant.BusinessAssistantService assistant) {
        this.parser=parser;this.codec=codec;this.store=store;this.planner=planner;this.conversations=conversations;
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
        // 业务查询独立维护上下文，不调用beginRequest、不刷新派单预览，也不改变原勾选集合。
        if(assistant.handle(user,id,requestId,message,model,session,guard,emit)) return;
        try {
            hydrate(user,id,state);
            state.setAttemptedAt(LocalDateTime.now());
            // 新消息使此前排队的界面查询失效；即使新意图被拒绝，也不能让迟到任务覆盖当前状态。
            long previewVersion=previews.beginRequest(id);
            catalog.refreshForValidation();
            var mentions=planner.mentions(user,message);
            var interpreted=parser.interpret(message,new IntentParser.Context(state,catalog.dispatchableReports(user).stream().map(CatalogEntry::ref).toList(),mentions,SemanticCapabilities.selectors(catalog.dispatchableReports(user)),draft -> planner.validateModelDraft(user,state,draft),SemanticCapabilities.fields(catalog.dispatchableReports(user))));
            codec.validate(interpreted.intent(),message); guard.run();
            intent=interpreted.intent();state.setParserSource(interpreted.source());
            // 普通查询结果没有派单资格语义；查询/派单切换时先取得新候选，禁止指代旧预览生成或修改清单。
            if(state.isBusinessQueryAfterPreview() && (intent.action()==SemanticIntent.Action.PREPARE_DISPATCH
                    || (intent.changes(RECORDS) && !intent.changes(REPORTS) && !intent.changes(COMPANY))
                    || (intent.action()==SemanticIntent.Action.CLARIFY && intent.clarify()==SemanticIntent.Clarify.RECORDS)))
                throw new ApiException(422,"刚才查看的是只读业务数据，请先明确查询可派单范围，再调整选择或生成待确认清单");
            state.setPendingIntent(intent);
            planner.requireCoverage(state,intent,mentions);
            planner.merge(user,state,intent);
            session.save();
            planner.requireAction(state,intent);
            reply=switch(intent.action()) {
                case HELP -> "可以查询某家公司或报表的可派单记录、追加或移除报表、排除单据、生成待确认清单，以及取消清单或查看结果。真正派单需要点击确认卡片。";
                case CANCEL_PLAN -> cancel(user,id,state,session,guard);
                case SHOW_RESULT -> result(user,id,state,emit);
                case PREVIEW, PREPARE_DISPATCH, EXPLAIN_RULES -> query(user,id,requestId,previewVersion,intent,state,session,uiPreviewId,uiExcludes,guard,emit);
                default -> throw new ApiException(422,"请明确本次操作");
            };
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
            DialogueStore.Session session,String uiPreviewId,List<RecordKey> uiExcludes,Runnable guard,Consumer<AgentEvent> emit) {
        planner.validate(user,state);
        PreviewSnapshot snapshot=null;
        boolean afterBusinessQuery=state.isBusinessQueryAfterPreview();
        boolean sameScope=Objects.equals(state.getDesired(),state.getEffective());
        if (sameScope && state.getPreviewId()!=null) {
            var saved=previews.getOwned(user,state.getPreviewId());
            if (DispatchPreview.ACTIVE.equals(saved.preview().getStatus())) snapshot=saved;
        }
        boolean keepsReports=intent.reportConstraints().stream().anyMatch(c->c.role()==SemanticIntent.ReportRole.UNCHANGED || c.role()==SemanticIntent.ReportRole.UNCHANGED_OTHERS);
        boolean onlySelection=(intent.changes(RECORDS) || keepsReports) && !intent.changes(COMPANY) && !intent.changes(REPORTS);
        if(keepsReports && snapshot==null && state.getPreviewId()!=null)
            throw new ApiException(422,"原预览已变化，无法保持原选择，请先重新查询再指定选择");
        String previousPreviewId=state.getPreviewId();
        boolean refreshed=false;
        boolean resetsRecords=intent.changesFor(RECORDS).stream().anyMatch(c ->
                c.operation()==SemanticIntent.Operation.CLEAR || c.operation()==SemanticIntent.Operation.REPLACE);
        if (snapshot==null || (intent.action()==SemanticIntent.Action.PREVIEW && !onlySelection)) {
            state.setPhase(DialogueState.Phase.QUERYING); session.save();
            // Stream factual progress; the final count only comes from the completed snapshot.
            emit.accept(new AgentEvent(AgentEvent.TEXT,Map.of("delta","正在查询可派单记录。\n")));
            var scope=state.getDesired();
            var command=new PreviewCommand("PREVIEW", "semantic", null, scope.allReports()?List.of():scope.reportIds(), new PreviewCommand.Filters(scope.companyCode()), "replace");
            var outcome=previews.preview(user,id,command,n -> guard.run(),pid -> session.fenced(() -> { guard.run(); return null; }),previewVersion);
            if (outcome.status()!=PreviewOutcome.Status.OK) throw new ApiException(422,"当前范围没有可用报表，请重新指定报表名称");
            snapshot=outcome.snapshot();
            state.setPreviewId(snapshot.preview().getId()); state.setEffective(scope); state.setPlanId(null);
            state.setBusinessQueryAfterPreview(false);
            if(!sameScope){state.setLastSuccessfulSelection(List.of());state.setLastSelectionReferences(List.of());state.setLastSelectionReferencesComplete(true);}
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
        if (refreshed && !state.getExcludedRecords().isEmpty() && !resetsRecords) {
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
                selected=SelectionResolver.apply(rows,selected,change,catalog,user);
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
            if (snapshot.preview().getTotalCount()==0) return "当前查询结果为 0 条，没有可生成清单的记录。";
            var plan=session.fenced(() -> { guard.run(); return plans.create(user,id,state.getPreviewId(),state.getExcludedRecords(),"semantic:"+requestId); });
            state.setPlanId(plan.plan().getId()); state.setPhase(DialogueState.Phase.PLAN_READY);
            var payload=PlanPayload.of(plan); conversations.logCard(id,user.userId(),"plan",payload,state.getPreviewId(),state.getPlanId());
            emit.accept(new AgentEvent(AgentEvent.PLAN,payload));
            return "已生成待确认清单，共 "+payload.count()+" 条。请核对卡片并点击“确认派单”后执行。";
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
        Set<RecordKey> keys=new HashSet<>(SelectionResolver.apply(rows,List.of(),target,catalog,user));
        var references=new ArrayList<Map<String,String>>();int bytes=0;boolean complete=true;
        for(var row:rows)if(keys.contains(new RecordKey(row.reportId(),row.recordId()))) {
            var ref=new LinkedHashMap<String,String>();ref.put("reportId",row.reportId());ref.put("reportName",row.reportName());ref.put("companyCode",row.companyCode());
            if(row.docNo()!=null)ref.put("docNo",row.docNo());if(row.label()!=null)ref.put("label",row.label());
            for(var field:row.fields())if(field.value()!=null)ref.putIfAbsent(field.name(),field.value());
            if(row.counterparty()!=null){ref.put("counterpartyId",row.counterparty().id());ref.put("counterpartyName",row.counterparty().name());}
            int size=JsonUtil.toJson(ref).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(references.size()>=50 || bytes+size>32768){complete=false;break;}
            references.add(ref);bytes+=size;
        }
        state.setLastSelectionReferences(List.copyOf(references));state.setLastSelectionReferencesComplete(complete);
    }
    private void hydrate(CurrentUser user,String id,DialogueState state) {
        previews.latest(user,id).ifPresent(latest -> {
            boolean initial=state.getAttemptedAt()==null;
            boolean explicitUi=Set.of("selection","api").contains(Objects.toString(latest.getSource(),""))
                    && state.getAttemptedAt()!=null && latest.getCreatedAt().isAfter(state.getAttemptedAt());
            if (!Objects.equals(latest.getId(),state.getPreviewId()) && (initial || explicitUi)) {
                var snapshot=previews.getOwned(user,latest.getId());
                if (!DispatchPreview.ACTIVE.equals(snapshot.preview().getStatus())) return;
                var query=snapshot.query(); Object filters=query.get("filters");
                String company=filters instanceof Map<?,?> m ? (String)m.get("companyCode") : null;
                var scope=new DialogueState.Scope(company,Boolean.TRUE.equals(query.get("allReports")),snapshot.reportIds());
                state.setDesired(scope);state.setEffective(scope);state.setPreviewId(latest.getId());state.setExcludedRecords(List.of());
                state.setUnresolvedReports(false);state.setUnresolvedCompany(false);state.setPhase(DialogueState.Phase.READY);
            }
        });
    }
    private PlanSnapshot latestPlan(CurrentUser user,String id,DialogueState state) {
        var latest=planRepository.byConversation(id).stream().max(Comparator.comparing(DispatchPlan::getCreatedAt))
                .orElseThrow(() -> new ApiException(422,"当前会话没有派单清单"));
        var snapshot=plans.getOwned(user,latest.getId()); state.setPlanId(latest.getId()); return snapshot;
    }
    private String cancel(CurrentUser user,String id,DialogueState state,DialogueStore.Session session,Runnable guard) {
        var snapshot=latestPlan(user,id,state);
        var plan=session.fenced(() -> { guard.run(); return plans.cancel(user,snapshot.plan().getId()); });
        state.setPhase(DialogueState.Phase.READY);
        return "当前清单状态："+statusName(plan.getStatus())+"。";
    }
    private String result(CurrentUser user,String id,DialogueState state,Consumer<AgentEvent> emit) {
        var snapshot=latestPlan(user,id,state); var plan=snapshot.plan();
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
            if(!current.equals(desired)){state.setLastSuccessfulSelection(List.of());state.setLastSelectionReferences(List.of());state.setLastSelectionReferencesComplete(true);}
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
    private static String friendly(Exception e) { return e instanceof ApiException ? SensitiveData.text(e.getMessage()) : "本次处理暂时失败，请稍后重试；尚未确认的清单不会执行派单。"; }
}
