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
import static com.example.report.semantic.SemanticIntent.Operation.KEEP;

/** Domain/model interpretation -> authoritative state -> deterministic business services -> factual replies. */
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
    public SemanticConversationService(IntentParser parser, IntentCodec codec, DialogueStore store, SemanticPlanner planner,
            ConversationService conversations, PreviewService previews, PlanService plans, PlanRepository planRepository,
            ReportCatalogService catalog, ResourceQuotaService quotas, AgentProperties props) {
        this.parser=parser;this.codec=codec;this.store=store;this.planner=planner;this.conversations=conversations;
        this.previews=previews;this.plans=plans;this.planRepository=planRepository;this.catalog=catalog;this.quotas=quotas;this.props=props;
        if (!Set.of("active","legacy").contains(props.getSemantic().getMode())) throw new IllegalArgumentException("agent.semantic.mode must be active or legacy");
    }
    public boolean enabled() { return "active".equals(props.getSemantic().getMode()); }
    public String modelName(String fallback) {
        String configured=props.getSemantic().getModel();return configured==null || configured.isBlank()?fallback:configured;
    }
    public Flux<ServerSentEvent<Object>> chat(CurrentUser user,String conversationId,String message,
            List<String> legacyExcludes,String uiPreviewId,List<RecordKey> excludedRecords,String model) {
        var conversation=conversationId==null || conversationId.isBlank() ? conversations.create(user,model) : conversations.getOwned(user,conversationId);
        String id=conversation.getId(), requestId=JsonUtil.newId();
        previews.requireConversationReadable(user,id);
        Flux<AgentEvent> events=Flux.<AgentEvent>create(sink -> {
            try (var permit=quotas.acquire(user,"chat",List.of()); var session=store.acquire(user,id)) {
                sink.next(new AgentEvent(AgentEvent.CONVERSATION,Map.of("conversationId",id,"requestId",requestId,"title",Objects.toString(conversation.getTitle(),""))));
                Runnable guard=() -> { session.check(); ResourceQuotaService.check(permit); if (sink.isCancelled()) throw new ApiException(409,"对话连接已关闭"); };
                Consumer<AgentEvent> emit=event -> { guard.run(); sink.next(event); };
                turn(user,id,requestId,message,legacyExcludes,uiPreviewId,excludedRecords,model,session,guard,emit);
                sink.next(new AgentEvent(AgentEvent.DONE,Map.of()));
                sink.complete();
            } catch (Exception failure) {
                log.warn("语义对话未完成 conversation={} type={}",id,failure.getClass().getSimpleName());
                sink.next(new AgentEvent(AgentEvent.ERROR,Map.of("message",friendly(failure))));
                sink.next(new AgentEvent(AgentEvent.DONE,Map.of())); sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofSeconds(store.timeoutSeconds()+5))
                .onErrorResume(e -> Flux.just(new AgentEvent(AgentEvent.ERROR,Map.of("message","本次处理超时，请刷新会话查看已保存的结果后重试")),new AgentEvent(AgentEvent.DONE,Map.of())));
        return events.index().map(e -> ServerSentEvent.builder((Object)SensitiveData.typed(e.getT2().data()))
                .id(requestId+":"+e.getT1()).event(e.getT2().type()).build());
    }
    private void turn(CurrentUser user,String id,String requestId,String message,List<String> legacyExcludes,
            String uiPreviewId,List<RecordKey> uiExcludes,String model,DialogueStore.Session session,Runnable guard,Consumer<AgentEvent> emit) {
        long started=System.nanoTime();
        var state=session.state(); SemanticIntent intent=null; String reply;
        state.setParserSource(null);
        conversations.logUser(id,user.userId(),message);
        try {
            hydrate(user,id,state);
            state.setAttemptedAt(LocalDateTime.now());
            // A new utterance supersedes queued legacy/UI work, even if this utterance is rejected later.
            long previewVersion=previews.beginRequest(id);
            catalog.refreshForValidation();
            var mentions=planner.mentions(user,message);
            var interpreted=parser.interpret(message,new IntentParser.Context(state,catalog.dispatchableReports(user).stream().map(CatalogEntry::ref).toList(),mentions));
            intent=interpreted.intent();state.setParserSource(interpreted.source());
            codec.validate(intent,message); guard.run();
            state.setPendingIntent(intent);
            planner.merge(user,state,intent);
            planner.requireCoverage(state,intent,mentions);
            session.save();
            planner.requireAction(state,intent);
            reply=switch(intent.action()) {
                case HELP -> "可以查询某家公司或报表的可派单记录、追加或移除报表、排除单据、生成待确认清单，以及取消清单或查看结果。真正派单需要点击确认卡片。";
                case CANCEL_PLAN -> cancel(user,id,state,session,guard);
                case SHOW_RESULT -> result(user,id,state,emit);
                case PREVIEW, PREPARE_DISPATCH, EXPLAIN_RULES -> query(user,id,requestId,previewVersion,intent,state,session,legacyExcludes,uiPreviewId,uiExcludes,guard,emit);
                default -> throw new ApiException(422,"请明确本次操作");
            };
            state.setLastReason(null);
        } catch (Exception failure) {
            if (intent==null) {
                // An unparsed request may have changed either scope; later ellipsis cannot execute against old data.
                state.setUnresolvedCompany(true);state.setUnresolvedReports(true);
            }
            state.setPhase(failure instanceof ApiException api && api.getCode()==422 ? DialogueState.Phase.CLARIFY
                    : failure instanceof ApiException ? DialogueState.Phase.REJECTED : DialogueState.Phase.FAILED);
            reply=friendly(failure); state.setLastReason(reply);
            if (!(failure instanceof ApiException)) log.warn("语义处理失败 conversation={} type={}",id,failure.getClass().getSimpleName());
        }
        var recent=new ArrayList<>(state.getRecentUserMessages()); recent.add(SensitiveData.text(message));
        state.setRecentUserMessages(List.copyOf(recent.subList(Math.max(0,recent.size()-4),recent.size())));
        session.save();
        session.record(requestId,message,intent,state.getPhase().name(),state.getLastReason(),
                state.getParserSource()==IntentParser.Source.DOMAIN?"domain-grammar-v1":model,(System.nanoTime()-started)/1_000_000);
        conversations.logAssistant(id,user.userId(),reply,null,null,(System.nanoTime()-started)/1_000_000);
        emit.accept(new AgentEvent(AgentEvent.TEXT,Map.of("delta",reply)));
        emit.accept(new AgentEvent("selection",selection(state)));
    }
    private String query(CurrentUser user,String id,String requestId,long previewVersion,SemanticIntent intent,DialogueState state,
            DialogueStore.Session session,List<String> legacyExcludes,String uiPreviewId,List<RecordKey> uiExcludes,Runnable guard,Consumer<AgentEvent> emit) {
        planner.validate(user,state);
        PreviewSnapshot snapshot=null;
        boolean sameScope=Objects.equals(state.getDesired(),state.getEffective());
        if (sameScope && state.getPreviewId()!=null) {
            var saved=previews.getOwned(user,state.getPreviewId());
            if (DispatchPreview.ACTIVE.equals(saved.preview().getStatus())) snapshot=saved;
        }
        boolean onlySelection=intent.exclusions().operation()!=KEEP && intent.company().operation()==KEEP && intent.reports().operation()==KEEP;
        if (snapshot==null || (intent.action()==SemanticIntent.Action.PREVIEW && !onlySelection)) {
            state.setPhase(DialogueState.Phase.QUERYING); session.save();
            // Stream factual progress; the final count only comes from the completed snapshot.
            emit.accept(new AgentEvent(AgentEvent.TEXT,Map.of("delta","正在查询可派单记录。\n")));
            var scope=state.getDesired();
            var command=new PreviewCommand("PREVIEW","semantic",null,scope.allReports()?List.of():scope.reportIds(),new PreviewCommand.Filters(scope.companyCode()),List.of(),"replace");
            var outcome=previews.preview(user,id,command,n -> guard.run(),pid -> session.fenced(() -> { guard.run(); return null; }),previewVersion);
            if (outcome.status()!=PreviewOutcome.Status.OK) throw new ApiException(422,"当前范围没有可用报表，请重新指定报表名称");
            snapshot=outcome.snapshot();
            state.setPreviewId(snapshot.preview().getId()); state.setEffective(scope); state.setPlanId(null); state.setExcludedRecords(List.of());
            session.save();
            var payload=PreviewPayload.of(snapshot,catalog);
            conversations.logCard(id,user.userId(),"preview",payload,payload.previewId(),null);
            emit.accept(new AgentEvent(AgentEvent.PREVIEW,payload));
        }
        boolean uiMatches=Objects.equals(uiPreviewId,state.getPreviewId());
        if (uiPreviewId!=null && !uiMatches && uiExcludes!=null && !uiExcludes.isEmpty())
            throw new ApiException(422,"查询范围已变化，旧预览的勾选未应用；请在新预览上重新选择后派单");
        if (state.isUnresolvedRecords() && intent.exclusions().operation()==KEEP
                && (!uiMatches || uiExcludes==null || uiExcludes.equals(state.getExcludedRecords())))
            throw new ApiException(422,"上次排除记录尚未确定，请说明准确单据号、明确恢复全部记录，或在表格中重新选择");
        if (intent.exclusions().operation()!=KEEP || (uiMatches && uiExcludes!=null && !uiExcludes.isEmpty()) || (uiMatches && legacyExcludes!=null && !legacyExcludes.isEmpty())) {
            state.setUnresolvedRecords(true);
            var rows=rows(user,snapshot);
            List<RecordKey> selected=uiMatches && uiExcludes!=null ? List.copyOf(uiExcludes) : state.getExcludedRecords();
            if (uiMatches && legacyExcludes!=null && !legacyExcludes.isEmpty()) selected=SelectionResolver.apply(rows,selected,
                    new SemanticIntent.Change(SemanticIntent.Operation.ADD,legacyExcludes,""));
            SelectionResolver.validate(rows,selected);
            state.setExcludedRecords(SelectionResolver.apply(rows,selected,intent.exclusions()));
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
            var plan=session.fenced(() -> { guard.run(); return plans.create(user,id,state.getPreviewId(),List.of(),"semantic:"+requestId,state.getExcludedRecords()); });
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
        return rows;
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
    private static Map<String,Object> selection(DialogueState state) {
        Map<String,Object> value=new LinkedHashMap<>(); value.put("previewId",state.getPreviewId()); value.put("excludedRecords",state.getExcludedRecords()); value.put("phase",state.getPhase()); return value;
    }
    private static String friendly(Exception e) { return e instanceof ApiException ? SensitiveData.text(e.getMessage()) : "本次处理暂时失败，请稍后重试；尚未确认的清单不会执行派单。"; }
}
