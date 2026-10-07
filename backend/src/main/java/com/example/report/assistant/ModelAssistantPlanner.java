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

/** 真实模型的统一业务路由与查询规划；不注册执行工具，不传身份和物理表名，最多一次格式修正。 */
@Component
public class ModelAssistantPlanner implements AssistantPlanner {
    private final ChatClient client;
    private final AgentProperties props;
    private final String instructions;
    public ModelAssistantPlanner(ChatModel model,AgentProperties props) {
        this.client=ChatClient.builder(model).build();this.props=props;
        try(var in=new ClassPathResource("assistant/planner-instructions.txt").getInputStream()){instructions=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        catch(Exception e){throw new IllegalStateException("业务助手规则无法读取",e);}
    }
    @Override public AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies) {
        var protectedInput=SensitiveData.modelText(message);
        var originals=new LinkedHashMap<>(protectedInput.originals());
        var input=new LinkedHashMap<String,Object>();input.put("message",protectedInput.text());
        input.put("today",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());input.put("companies",companies.stream().sorted().toList());
        input.put("reports",reports.stream().map(r->Map.of("reportId",r.reportId(),"name",r.reportName(),"aliases",r.ref().aliases(),"fields",BusinessFields.forDomain(BusinessQuery.Domain.REPORT,r.fields()))).toList());
        input.put("dispatchFields",BusinessFields.forDomain(BusinessQuery.Domain.DISPATCH,List.of()));input.put("workOrderFields",BusinessFields.forDomain(BusinessQuery.Domain.WORK_ORDER,List.of()));
        input.put("previousQuery",protectContext(JsonUtil.MAPPER.valueToTree(state.getBusinessQuery()),originals));
        input.put("previousRows",protectContext(JsonUtil.MAPPER.valueToTree(state.getBusinessReferences()),originals));input.put("lastRoute",state.getAssistantFocus());input.put("lastAttemptRoute",state.getAssistantRoute());
        input.put("queryUnresolved",state.isBusinessUnresolved());input.put("dispatchPreviewPresent",state.getPreviewId()!=null);
        input.put("dispatchPlanPresent",state.getPlanId()!=null);input.put("dispatchUnresolved",state.isUnresolvedRequest());
        var dispatchContext=new LinkedHashMap<String,Object>();dispatchContext.put("desired",state.getDesired());dispatchContext.put("effective",state.getEffective());
        dispatchContext.put("phase",state.getPhase());dispatchContext.put("unresolvedCompany",state.isUnresolvedCompany());dispatchContext.put("unresolvedReports",state.isUnresolvedReports());dispatchContext.put("unresolvedRecords",state.isUnresolvedRecords());
        input.put("dispatchContext",dispatchContext);
        var options=OpenAiChatOptions.builder().temperature(0.0).maxTokens(3000).internalToolExecutionEnabled(false).toolNames(Set.of()).toolCallbacks(List.of())
                .extraBody(Map.of("thinking",Map.of("type","disabled"))).outputSchema(AssistantSchema.planSchema());
        if(props.getSemantic().getModel()!=null && !props.getSemantic().getModel().isBlank()) options.model(props.getSemantic().getModel());
        for(int attempt=0;attempt<2;attempt++) {
            String modelInput=JsonUtil.toJson(SensitiveData.forModel(input));
            if(modelInput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128000)throw new ApiException(422,"当前目录上下文超过查询规划预算，请联系管理员限定助手可见目录");
            String reply=client.prompt().system(instructions+"\nJSON Schema:\n"+AssistantSchema.planSchema())
                    .user(modelInput).options(options.build()).call().content();
            try {
                var plan=AssistantCodec.plan(reply);
                // 脱敏占位只恢复模型输出的字符串值；不将来源文本变成指令或新增字段。
                plan=AssistantCodec.plan(restore(JsonUtil.MAPPER.valueToTree(plan),new SensitiveData.ModelText(protectedInput.text(),originals)).toString());
                AssistantRouteGuard.validate(message,state,plan);
                if(plan.route()==AssistantPlan.Route.BUSINESS_QUERY && plan.followUp() && (state.getBusinessQuery()==null || state.isBusinessUnresolved()))
                    throw new ApiException(422,"上次查询未完成，请重新明确查询对象和筛选条件");
                // 追问只能细化同一业务对象；跨域须明确发起独立查询，不能在省略对象时静默换域。
                if(plan.route()==AssistantPlan.Route.BUSINESS_QUERY && plan.followUp() && plan.query().domain()!=state.getBusinessQuery().domain())
                    throw new ApiException(422,"连续查询必须保留上一查询的数据域；仅调整状态、分组或分页不能改查另一类业务对象，请依据原文重新规划");
                return plan;
            } catch(ApiException invalid) {
                if(attempt==1) throw invalid;
                input.put("rejectedDraft",SensitiveData.text(reply==null?"":reply.length()>24000?"[超长输出]":reply));input.put("validationError",invalid.getMessage());
            }
        }
        throw new ApiException(422,"请明确查询对象");
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
