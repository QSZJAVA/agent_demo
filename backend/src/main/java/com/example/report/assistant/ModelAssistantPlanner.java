package com.example.report.assistant;

import com.example.report.catalog.CatalogEntry;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.operations.SensitiveData;
import com.example.report.semantic.DialogueState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.util.*;

/** 真实模型的统一任务规划与盲式语义复核；独立期望在本轮冻结并经程序比较，最多三次规划和三次复核。 */
@Component
public class ModelAssistantPlanner implements AssistantPlanner {
    private final ChatClient client;
    private final AgentProperties props;
    private final String instructions;
    private final String reviewInstructions;
    public ModelAssistantPlanner(ChatModel model,AgentProperties props) {
        this.client=ChatClient.builder(model).build();this.props=props;
        try(var in=new ClassPathResource("assistant/planner-instructions.txt").getInputStream();
            var contract=new ClassPathResource("assistant/task-contract.txt").getInputStream();
            var dispatch=new ClassPathResource("assistant/dispatch-instructions.txt").getInputStream();
            var review=new ClassPathResource("assistant/review-instructions.txt").getInputStream()) {
            var taskRules=new String(contract.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            var dispatchRules=new String(dispatch.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            // 原生Schema仍随请求发送；同时把同一目标结构交给规划器阅读，防止将输入上下文字段误抄到输出。
            instructions="完整输出JSON Schema（只输出该对象，不输出Schema本身或任何输入上下文字段）：\n"+AssistantSchema.planSchema()
                    +"\n仅在用户要求派单子流程时适用的字段手册：\n"+dispatchRules
                    +"\n所有动作共同遵守的任务契约：\n"+taskRules+"\n"+new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            // 两阶段读取同一完整业务契约，复核不能自行发明另一套查询、对象或生命周期规则。
            reviewInstructions="完整复核JSON Schema：\n"+AssistantSchema.reviewSchema()
                    +"\n仅在用户要求派单子流程时适用的字段手册（描述expectedPlan.dispatch）：\n"+dispatchRules
                    +"\n所有动作共同遵守的任务契约（描述expectedPlan，不改变复核根对象）：\n"+taskRules
                    +"\n"+new String(review.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        catch(Exception e){throw new IllegalStateException("业务助手规则无法读取",e);}
    }
    @Override public AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies) {
        return plan(message,state,reports,companies,draft->{});
    }
    @Override public AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies,
                                       java.util.function.Consumer<AssistantPlan> validateDraft) {
        return plan(message,state,reports,companies,AssistantPlanningContext.empty(validateDraft));
    }
    @Override public AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies,AssistantPlanningContext context) {
        context=context.beforeCurrentMessage(message);
        var protectedInput=SensitiveData.modelText(message);
        var originals=new LinkedHashMap<>(protectedInput.originals());
        var input=new LinkedHashMap<String,Object>();input.put("messageIndex",-1);input.put("message",protectedInput.text());
        input.put("today",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());input.put("companies",companies.stream().sorted().toList());
        input.put("reports",reports.stream().map(r->Map.of("reportId",r.reportId(),"name",r.reportName(),"aliases",r.ref().aliases(),
                "description",Objects.toString(r.ref().description(),""),"dispatchEnabled",r.dispatchEnabled(),"fields",BusinessFields.forDomain(BusinessQuery.Domain.REPORT,r.fields()))).toList());
        input.put("dispatchFields",BusinessFields.forDomain(BusinessQuery.Domain.DISPATCH,List.of()));input.put("workOrderFields",BusinessFields.forDomain(BusinessQuery.Domain.WORK_ORDER,List.of()));
        input.put("previousQuery",protectContext(JsonUtil.MAPPER.valueToTree(state.getBusinessQuery()),originals));
        input.put("previousRows",protectContext(JsonUtil.MAPPER.valueToTree(state.getBusinessReferences()),originals));input.put("lastRoute",state.getAssistantFocus());input.put("lastAttemptRoute",state.getAssistantRoute());
        // 只提供上一成功查询的计数，不加载未展示记录；分页或失败状态不能声称当前页覆盖全集。
        var previousResult=new LinkedHashMap<String,Object>();previousResult.put("totalCount",state.getBusinessTotalCount());
        previousResult.put("displayedCount",state.getBusinessReferences().size());
        previousResult.put("allMatchesDisplayed",!state.isBusinessUnresolved() && state.getBusinessQuery()!=null
                && state.getBusinessQuery().page()==1 && state.getBusinessTotalCount()!=null
                && state.getBusinessTotalCount()==(long)state.getBusinessReferences().size());
        input.put("previousResult",previousResult);
        input.put("queryObjects",protectContext(JsonUtil.MAPPER.valueToTree(AssistantReferences.queryContext(state)),originals));
        // 把引用位置作为显式数据发送，模型只复制messageIndex，不要求它自行数长对话数组。
        var indexedHistory=new ArrayList<Map<String,Object>>();
        for(int index=0;index<context.history().size();index++) {
            var entry=new LinkedHashMap<String,Object>();entry.putAll(context.history().get(index));entry.put("messageIndex",index);indexedHistory.add(entry);
        }
        input.put("recentConversation",protectContext(JsonUtil.MAPPER.valueToTree(indexedHistory),originals));
        input.put("dispatchSelection",protectContext(JsonUtil.MAPPER.valueToTree(context.dispatchSelection()),originals));
        input.put("queryUnresolved",state.isBusinessUnresolved());input.put("dispatchPreviewPresent",state.getPreviewId()!=null);
        input.put("dispatchPlanPresent",state.getPlanId()!=null);input.put("dispatchUnresolved",state.isUnresolvedRequest());
        // 路由层也需要真实子流程能力，不能把已实现的配置字段勾选误称为不支持。
        var dispatchable=reports.stream().filter(CatalogEntry::dispatchEnabled).toList();
        var dispatchCapabilities=new LinkedHashMap<>(com.example.report.semantic.SemanticCapabilities.describe(com.example.report.semantic.SemanticCapabilities.selectors(dispatchable)));
        // 子流程的默认选择动作不是统一入口的默认路由，避免无派单授权的普通查询受执行细节牵引。
        dispatchCapabilities.remove("selectionDefaults");input.put("dispatchCapabilities",dispatchCapabilities);
        input.put("dispatchFieldsByReport",com.example.report.semantic.SemanticCapabilities.fields(dispatchable));
        var dispatchContext=new LinkedHashMap<String,Object>();dispatchContext.put("desired",state.getDesired());dispatchContext.put("effective",state.getEffective());
        dispatchContext.put("phase",state.getPhase());dispatchContext.put("unresolvedCompany",state.isUnresolvedCompany());dispatchContext.put("unresolvedReports",state.isUnresolvedReports());dispatchContext.put("unresolvedRecords",state.isUnresolvedRecords());
        dispatchContext.put("previewRef",AssistantReferences.previewRef(state));dispatchContext.put("planRef",AssistantReferences.planRef(state));
        dispatchContext.put("requiresRedisplayBeforePrepare",state.isBusinessQueryAfterPreview());
        dispatchContext.put("lastSelectionReferences",protectContext(JsonUtil.MAPPER.valueToTree(state.getLastSelectionReferences()),originals));
        dispatchContext.put("lastSelectionReferencesComplete",state.isLastSelectionReferencesComplete());
        dispatchContext.put("lastFailure",state.getLastReason());
        input.put("dispatchContext",protectContext(JsonUtil.MAPPER.valueToTree(dispatchContext),originals));
        var options=options(AssistantSchema.planSchema());
        if(props.getSemantic().getModel()!=null && !props.getSemantic().getModel().isBlank()) options.model(props.getSemantic().getModel());
        var attempts=new ArrayList<Map<String,Object>>();input.put("attemptHistory",attempts);
        var budget=new ReviewBudget();
        var validated=new HashMap<AssistantPlan,Map<String,Object>>();SemanticReview expectation=null;
        for(int attempt=0;attempt<3;attempt++) {
            // 当前原文位于所有事实和历史反馈之后；源数据不能将旧动作提升为本轮用户要求。
            input.remove("message");input.put("message",protectedInput.text());
            String modelInput=JsonUtil.toJson(SensitiveData.forModel(input));
            if(modelInput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128000)throw new ApiException(422,"当前目录上下文超过查询规划预算，请联系管理员限定助手可见目录");
            String reply=client.prompt().system(instructions)
                    .user(modelInput).options(options.build()).call().content();
            AssistantPlan plan=null;Map<String,Object> facts=Map.of();ReviewedDraft reviewed=null;
            try {
                plan=AssistantCodec.plan(reply);
                // 脱敏占位只恢复模型输出的字符串值；不将来源文本变成指令或新增字段。
                plan=AssistantCodec.plan(restore(JsonUtil.MAPPER.valueToTree(plan),new SensitiveData.ModelText(protectedInput.text(),originals)).toString());
                facts=validateDraft(message,state,plan,context,validated);
                // 独立复核只接收原文命中的字段词义，不读取草稿或结果集合；命中不代表选中了对应记录。
                input.put("observedTerms",protectContext(JsonUtil.MAPPER.valueToTree(BusinessTermEvidence.extract(message,facts,reports)),originals));
                // 复核看不到草稿、草稿读出的结果和修正历史，避免为错误动作编造理由；合法期望只形成一次。
                if(expectation==null) {
                    reviewed=review(message,input,state,context,plan,new SensitiveData.ModelText(protectedInput.text(),originals),budget,validated);
                    expectation=reviewed.review();
                } else reviewed=new ReviewedDraft(expectation,TaskPlanComparison.compare(message,context.history(),plan,expectation));
                if(!reviewed.differences().isEmpty())throw new ApiException(422,"任务计划与本轮要求存在差异，请依据结构化反馈重新完整规划");
                TaskPlanComparison.validateTargetCount(expectation,facts);
                // 仅澄清表达可采用已复核文本；所有有业务含义的操作字段仍原样返回已预检的规划草稿。
                return plan.route()==AssistantPlan.Route.CLARIFY
                        ?new AssistantPlan(plan.route(),null,false,reviewed.review().expectedPlan().clarification()):plan;
            } catch(ApiException invalid) {
                // 权限、版本或业务服务故障不能诱导模型换成另一组可成功的目标；只有契约/语义错误可修正。
                if(attempt==2 || invalid.getCode()!=422 || invalid instanceof ReviewFailure) throw invalid;
                // 修正历史是有界事实而非指令；保留全部已确认差异，避免后一次反馈抹掉先前的目标限定。
                var previous=new LinkedHashMap<String,Object>();previous.put("attempt",attempt+1);
                previous.put("stage",reviewed==null?"PROGRAM_VALIDATION":"SEMANTIC_COMPARISON");
                previous.put("draft",plan==null?SensitiveData.text(reply==null?"":reply.length()>24000?"[超长输出]":reply):plan);
                previous.put("readEvidence",facts);
                previous.put("review",reviewed==null?null:reviewed.review());
                previous.put("differences",reviewed==null?List.of():reviewed.differences());
                previous.put("validationError",invalid instanceof com.example.report.semantic.IntentCodec.InvalidOutput structural?structural.reason():invalid.getMessage());
                attempts.add(JsonUtil.toMap(protectContext(JsonUtil.MAPPER.valueToTree(previous),originals).toString()));
                input.put("rejectedDraft",SensitiveData.text(reply==null?"":reply.length()>24000?"[超长输出]":reply));
                input.put("validationError",invalid instanceof com.example.report.semantic.IntentCodec.InvalidOutput structural?structural.reason():invalid.getMessage());
            }
        }
        throw new ApiException(422,"请明确查询对象");
    }
    /** 两份计划接受同一只读预检，按完整计划在本轮缓存；没有一份模型期望可直接产生预览或清单。 */
    private Map<String,Object> validateDraft(String message,DialogueState state,AssistantPlan plan,AssistantPlanningContext context,Map<AssistantPlan,Map<String,Object>> validated) {
        AssistantRouteGuard.validate(message,state,plan);
        if(plan.dispatch()!=null) {
            new com.example.report.semantic.IntentCodec().validate(plan.dispatch().intent(),message);
            AssistantReferences.validate(message,state,plan.dispatch());
        }
        return validated.computeIfAbsent(plan,context.validateDraft());
    }
    /** 独立期望只依据请求前上下文形成；自身结构或预检失败在盲式上下文中修复，不将草稿暴露给复核。 */
    private ReviewedDraft review(String message,Map<String,Object> context,DialogueState state,AssistantPlanningContext planning,AssistantPlan plan,SensitiveData.ModelText originals,ReviewBudget budget,Map<AssistantPlan,Map<String,Object>> validated) {
        var input=new LinkedHashMap<>(context);input.remove("rejectedDraft");input.remove("validationError");input.remove("attemptHistory");
        input.put("taskStage","INDEPENDENT_EXPECTATION");
        var replacements=new LinkedHashMap<>(originals.originals());
        var options=options(AssistantSchema.reviewSchema());
        if(props.getSemantic().getModel()!=null && !props.getSemantic().getModel().isBlank())options.model(props.getSemantic().getModel());
        while(budget.calls<3) {
            String wire=JsonUtil.toJson(SensitiveData.forModel(input));
            if(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>192000)throw new ReviewFailure("语义复核上下文超过预算，请缩小本轮业务范围");
            budget.calls++;
            String reply=client.prompt().system(reviewInstructions).user(wire).options(options.build()).call().content();
            try {
                var review=AssistantCodec.review(reply);
                review=AssistantCodec.review(restore(JsonUtil.MAPPER.valueToTree(review),new SensitiveData.ModelText(originals.text(),replacements)).toString());
                TaskPlanComparison.validateEvidence(message,planning.history(),review);
                var facts=validateDraft(message,state,review.expectedPlan(),planning,validated);
                TaskPlanComparison.validateTargetCount(review,facts);
                return new ReviewedDraft(review,TaskPlanComparison.compare(message,planning.history(),plan,review));
            } catch(ApiException invalid) {
                if(invalid.getCode()!=422)throw invalid;
                input.put("reviewValidationError",invalid instanceof com.example.report.semantic.IntentCodec.InvalidOutput structural?structural.reason():invalid.getMessage());
                input.put("invalidReview",SensitiveData.text(reply==null?"":reply.length()>24000?"[超长输出]":reply));
            }
        }
        throw new ReviewFailure("本轮语义复核未形成完整且一致的依据，请明确对象或操作后重试");
    }
    /**
     * 两阶段使用同一已配置模型能力；思考模式需要供应商实际支持，不改变原生Schema、工具禁用和有界重试。
     * @param schema 当前阶段的严格输出结构
     * @return 未绑定模型名称的选项构造器；启用思考时预留输出预算，仍受整轮超时保护
     */
    private OpenAiChatOptions.Builder options(String schema) {
        boolean thinking=props.getSemantic().isThinkingEnabled();
        return OpenAiChatOptions.builder().temperature(0.0).maxTokens(thinking?8192:4096)
                .internalToolExecutionEnabled(false).toolNames(Set.of()).toolCallbacks(List.of())
                .extraBody(Map.of("thinking",Map.of("type",thinking?"enabled":"disabled"))).outputSchema(schema);
    }
    /** 本轮共享调用计数；无效复核占用预算，但不自动消耗一次完整业务重规划。 */
    private static final class ReviewBudget {private int calls;}
    /** 无效或耗尽的复核不能转换成对业务草稿的修正命令。 */
    private static final class ReviewFailure extends ApiException {private ReviewFailure(String message){super(422,message);}}
    /**
     * 经原文和程序比较检查的复核结果。
     * @param review 结构化要求与完整期望
     * @param differences 确认存在且有本轮依据的行为差异；空集合表示通过
     */
    private record ReviewedDraft(SemanticReview review,List<TaskPlanComparison.Difference> differences) { }
    /** 只恢复 JSON 字符串叶子，由序列化器保留引号边界，原始实体不得拼接成新的协议字段。 */
    private static com.fasterxml.jackson.databind.JsonNode restore(com.fasterxml.jackson.databind.JsonNode node,SensitiveData.ModelText input) {
        if(node.isTextual())return new com.fasterxml.jackson.databind.node.TextNode(input.restore(node.textValue()));
        if(node.isObject()) {var object=(com.fasterxml.jackson.databind.node.ObjectNode)node;var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);for(String name:names)object.set(name,restore(object.get(name),input));}
        else if(node.isArray()){var array=(com.fasterxml.jackson.databind.node.ArrayNode)node;for(int i=0;i<array.size();i++)array.set(i,restore(array.get(i),input));}
        return node;
    }
    /** 跨轮引用也使用本次调用内可恢复占位符；不向模型发送原标识，也不把永久脱敏文本当成后续查询目标。 */
    private static com.fasterxml.jackson.databind.JsonNode protectContext(com.fasterxml.jackson.databind.JsonNode node,Map<String,String> originals) {
        if(node.isTextual()) {
            var value=SensitiveData.modelText(node.textValue());originals.putAll(value.originals());return new com.fasterxml.jackson.databind.node.TextNode(value.text());
        }
        if(node.isObject()){var object=(com.fasterxml.jackson.databind.node.ObjectNode)node;var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);for(String name:names)object.set(name,protectContext(object.get(name),originals));}
        else if(node.isArray()){var array=(com.fasterxml.jackson.databind.node.ArrayNode)node;for(int i=0;i<array.size();i++)array.set(i,protectContext(array.get(i),originals));}
        return node;
    }
}
