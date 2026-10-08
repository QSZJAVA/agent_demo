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

/** 真实模型的统一任务规划与独立语义复核；动作和对象一次绑定，只读反馈参与最多两次修正，模型不注册执行工具。 */
@Component
public class ModelAssistantPlanner implements AssistantPlanner {
    private final ChatClient client;
    private final AgentProperties props;
    private final String instructions;
    private final String reviewInstructions;
    public ModelAssistantPlanner(ChatModel model,AgentProperties props) {
        this.client=ChatClient.builder(model).build();this.props=props;
        try(var in=new ClassPathResource("assistant/planner-instructions.txt").getInputStream();
            var dispatch=new ClassPathResource("assistant/dispatch-instructions.txt").getInputStream();
            var review=new ClassPathResource("assistant/review-instructions.txt").getInputStream()) {
            instructions=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)+"\n派单操作字段的结构和语义补充：\n"+new String(dispatch.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            reviewInstructions=new String(review.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
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
        var protectedInput=SensitiveData.modelText(message);
        var originals=new LinkedHashMap<>(protectedInput.originals());
        var input=new LinkedHashMap<String,Object>();input.put("message",protectedInput.text());
        input.put("today",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());input.put("companies",companies.stream().sorted().toList());
        input.put("reports",reports.stream().map(r->Map.of("reportId",r.reportId(),"name",r.reportName(),"aliases",r.ref().aliases(),"dispatchEnabled",r.dispatchEnabled(),"fields",BusinessFields.forDomain(BusinessQuery.Domain.REPORT,r.fields()))).toList());
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
        input.put("recentConversation",protectContext(JsonUtil.MAPPER.valueToTree(context.history()),originals));
        input.put("dispatchSelection",protectContext(JsonUtil.MAPPER.valueToTree(context.dispatchSelection()),originals));
        input.put("queryUnresolved",state.isBusinessUnresolved());input.put("dispatchPreviewPresent",state.getPreviewId()!=null);
        input.put("dispatchPlanPresent",state.getPlanId()!=null);input.put("dispatchUnresolved",state.isUnresolvedRequest());
        // 路由层也需要真实子流程能力，不能把已实现的配置字段勾选误称为不支持。
        var dispatchable=reports.stream().filter(CatalogEntry::dispatchEnabled).toList();
        input.put("dispatchCapabilities",com.example.report.semantic.SemanticCapabilities.describe(com.example.report.semantic.SemanticCapabilities.selectors(dispatchable)));
        input.put("dispatchFieldsByReport",com.example.report.semantic.SemanticCapabilities.fields(dispatchable));
        var dispatchContext=new LinkedHashMap<String,Object>();dispatchContext.put("desired",state.getDesired());dispatchContext.put("effective",state.getEffective());
        dispatchContext.put("phase",state.getPhase());dispatchContext.put("unresolvedCompany",state.isUnresolvedCompany());dispatchContext.put("unresolvedReports",state.isUnresolvedReports());dispatchContext.put("unresolvedRecords",state.isUnresolvedRecords());
        dispatchContext.put("previewRef",AssistantReferences.previewRef(state));dispatchContext.put("planRef",AssistantReferences.planRef(state));
        dispatchContext.put("lastSelectionReferences",protectContext(JsonUtil.MAPPER.valueToTree(state.getLastSelectionReferences()),originals));
        dispatchContext.put("lastSelectionReferencesComplete",state.isLastSelectionReferencesComplete());
        dispatchContext.put("lastFailure",state.getLastReason());
        input.put("dispatchContext",protectContext(JsonUtil.MAPPER.valueToTree(dispatchContext),originals));
        var options=OpenAiChatOptions.builder().temperature(0.0).maxTokens(4096).internalToolExecutionEnabled(false).toolNames(Set.of()).toolCallbacks(List.of())
                .extraBody(Map.of("thinking",Map.of("type","disabled"))).outputSchema(AssistantSchema.planSchema());
        if(props.getSemantic().getModel()!=null && !props.getSemantic().getModel().isBlank()) options.model(props.getSemantic().getModel());
        for(int attempt=0;attempt<3;attempt++) {
            String modelInput=JsonUtil.toJson(SensitiveData.forModel(input));
            if(modelInput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128000)throw new ApiException(422,"当前目录上下文超过查询规划预算，请联系管理员限定助手可见目录");
            String reply=client.prompt().system(instructions)
                    .user(modelInput).options(options.build()).call().content();
            try {
                var plan=AssistantCodec.plan(reply);
                // 脱敏占位只恢复模型输出的字符串值；不将来源文本变成指令或新增字段。
                plan=AssistantCodec.plan(restore(JsonUtil.MAPPER.valueToTree(plan),new SensitiveData.ModelText(protectedInput.text(),originals)).toString());
                AssistantRouteGuard.validate(message,state,plan);
                if(plan.dispatch()!=null) {
                    new com.example.report.semantic.IntentCodec().validate(plan.dispatch().intent(),message);
                    AssistantReferences.validate(message,state,plan.dispatch());
                }
                var facts=context.validateDraft().apply(plan);
                review(message,input,plan,facts,new SensitiveData.ModelText(protectedInput.text(),originals));
                return plan;
            } catch(ApiException invalid) {
                // 权限、版本或业务服务故障不能诱导模型换成另一组可成功的目标；只有契约/语义错误可修正。
                if(attempt==2 || invalid.getCode()!=422) throw invalid;
                input.put("rejectedDraft",SensitiveData.text(reply==null?"":reply.length()>24000?"[超长输出]":reply));
                input.put("validationError",invalid instanceof com.example.report.semantic.IntentCodec.InvalidOutput structural?structural.reason():invalid.getMessage());
            }
        }
        throw new ApiException(422,"请明确查询对象");
    }
    /** 语义复核对比本轮原文、可靠对象上下文及实际只读事实；只返回修正意见，不执行或改写计划。 */
    private void review(String message,Map<String,Object> context,AssistantPlan plan,Map<String,Object> facts,SensitiveData.ModelText originals) {
        var input=new LinkedHashMap<>(context);input.remove("rejectedDraft");input.remove("validationError");
        var replacements=new LinkedHashMap<>(originals.originals());
        input.put("proposedPlan",protectContext(JsonUtil.MAPPER.valueToTree(plan),replacements));
        input.put("readEvidence",protectContext(JsonUtil.MAPPER.valueToTree(facts),replacements));
        String wire=JsonUtil.toJson(SensitiveData.forModel(input));
        if(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>192000)throw new ApiException(422,"语义复核上下文超过预算，请缩小本轮业务范围");
        var options=OpenAiChatOptions.builder().temperature(0.0).maxTokens(1600).internalToolExecutionEnabled(false).toolNames(Set.of()).toolCallbacks(List.of())
                .extraBody(Map.of("thinking",Map.of("type","disabled"))).outputSchema(AssistantSchema.reviewSchema());
        if(props.getSemantic().getModel()!=null && !props.getSemantic().getModel().isBlank())options.model(props.getSemantic().getModel());
        String reply=client.prompt().system(reviewInstructions).user(wire).options(options.build()).call().content();
        var review=AssistantCodec.review(reply);
        review=AssistantCodec.review(restore(JsonUtil.MAPPER.valueToTree(review),new SensitiveData.ModelText(originals.text(),replacements)).toString());
        for(var issue:review.issues())if(!message.contains(issue.evidence()))throw new ApiException(422,"语义复核依据必须来自本轮原文");
        if(!review.approved())throw new ApiException(422,"任务计划尚未完整对应本轮要求："+review.issues().stream()
                .map(issue->"原文“"+issue.evidence()+"”："+issue.reason()).collect(java.util.stream.Collectors.joining("；")));
    }
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
