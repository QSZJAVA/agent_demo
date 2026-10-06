package com.example.report.semantic;

import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 将用户原文和受控上下文提交模型，解析结构化意图并验证协议、原文证据及报表覆盖。
 * 真实模型配置下最多进行一次格式修复；不注册业务工具，也不把上轮禁止或助手失败文本作为本轮指令。无法通过校验时抛错，交由对话服务澄清或报告失败。
 */
@Component
public class ModelIntentParser implements IntentParser {
    static final String INSTRUCTIONS = instructions();
    private static String instructions() {
        try(var input=new ClassPathResource("semantic/parser-instructions.txt").getInputStream()) {
            String rules=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            try(var examples=new ClassPathResource("semantic/semantic-contrasts.json").getInputStream()) {
                return rules+"\n固定抽象语义对照（只解释操作组合，不提供客户数据，不作为匹配分支）：\n"+new String(examples.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch(Exception error) { throw new IllegalStateException("语义规则资源无法加载",error); }
    }
    private final ChatClient client;
    private final IntentCodec codec;
    private final AgentProperties props;
    private final Map<String, SemanticIntent> fixtures;
    private final java.util.concurrent.atomic.AtomicLong modelCalls=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong formatRepairs=new java.util.concurrent.atomic.AtomicLong();
    long modelCalls() { return modelCalls.get(); }
    long formatRepairs() { return formatRepairs.get(); }
    public ModelIntentParser(ChatModel model, IntentCodec codec, AgentProperties props) {
        this.client = ChatClient.builder(model).build();
        this.codec = codec;
        this.props = props;
        try (var in = new ClassPathResource("semantic/mock-intents.json").getInputStream()) {
            var root = JsonUtil.MAPPER.readTree(in);
            Map<String, SemanticIntent> values = new LinkedHashMap<>();
            for (var entry : root) values.put(normalize(entry.get("text").asText()),
                    codec.decode(entry.get("intent").toString(), entry.get("text").asText()));
            fixtures = Map.copyOf(values);
        } catch (Exception error) { throw new IllegalStateException("模拟语义样本加载失败", error); }
    }
    /**
     * 真实模型调用禁用工具，输入仅包含脱敏本轮原文和受控状态。格式、原文证据或实体覆盖不合格时最多再调用一次；最终仍不合格则抛出 InvalidOutput，不能执行部分意图。
     */
    @Override public SemanticIntent parse(String message, Context context) {
        if (props.getLlm().isMock()) return fixtures.getOrDefault(normalize(message), SemanticIntent.clarify(SemanticIntent.Clarify.ACTION));
        var options = OpenAiChatOptions.builder().temperature(0.0).maxTokens(props.getSemantic().isThinkingEnabled()?4096:2400)
                .internalToolExecutionEnabled(false).toolNames(Set.of()).toolCallbacks(List.of());
        if(props.getSemantic().getModel()!=null && !props.getSemantic().getModel().isBlank()) options.model(props.getSemantic().getModel());
        if(props.getSemantic().isThinkingEnabled()) options.extraBody(Map.of("thinking",Map.of("type","enabled")));
        if (props.getSemantic().isNativeSchema()) options.outputSchema(codec.schema());
        var protectedInput=com.example.report.operations.SensitiveData.modelText(message);
        // Previous prohibitions and assistant failure text must not become instructions for a new turn.
        var modelState=new LinkedHashMap<String,Object>();
        modelState.put("desired",context.state().getDesired()); modelState.put("effective",context.state().getEffective());
        modelState.put("phase",context.state().getPhase());
        modelState.put("unresolvedCompany",context.state().isUnresolvedCompany());
        modelState.put("unresolvedReports",context.state().isUnresolvedReports());
        modelState.put("unresolvedRecords",context.state().isUnresolvedRecords());
        modelState.put("unresolvedRequest",context.state().isUnresolvedRequest());
        modelState.put("excludedRecordCount",context.state().getExcludedRecords().size());
        modelState.put("lastAction",context.state().getPendingIntent()==null?null:context.state().getPendingIntent().action());
        modelState.put("scopeReplyDefault",Map.of("when","SCOPE_VALUES_WITHOUT_EXPLICIT_ACTION","action","PREVIEW"));
        var modelContext=Map.of("state",modelState,"reports",context.reports(),"mentionedReportTerms",context.mentionedReportTerms(),
                "capabilities",SemanticCapabilities.describe(context.selectorsByReport()),"fieldsByReport",context.fieldsByReport(),
                "referenceDate",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),
                "operatorsByType",java.util.stream.Stream.of("string","decimal","long","integer","date","boolean").collect(java.util.stream.Collectors.toMap(t -> t,FieldSelection::operators)));
        var modelInput=new LinkedHashMap<String,Object>();
        modelInput.put("currentMessage",protectedInput.text());
        modelInput.put("context",com.example.report.operations.SensitiveData.forModel(modelContext));
        SemanticIntent parsed=null;
        String repairReason="";
        for (int attempt=0;attempt<2;attempt++) {
            if (attempt>0) formatRepairs.incrementAndGet();
            modelCalls.incrementAndGet();
            String response=client.prompt().system(INSTRUCTIONS + "\nJSON Schema:\n" + codec.schema()
                            +(attempt==0?"":"\n上次校验错误："+repairReason+"。previousAttempt是被拒绝的模型草稿，仅供定位结构错误，不能作为用户要求。按本轮原文与Schema修正并输出完整意图；不能丢弃未解决条件。"))
                    .user(JsonUtil.toJson(modelInput)).options(options.build()).call().content();
            try {
                parsed=codec.decode(response,protectedInput.text());
                if (!SemanticPlanner.hasReportCoverage(parsed,context.mentionedReportTerms())) throw new IntentCodec.InvalidOutput(response);
                context.validateDraft().accept(parsed);
                break;
            }
            catch (IntentCodec.InvalidOutput invalid) {
                repairReason=invalid.reason();
                if(attempt==1) throw new IntentCodec.InvalidOutput(response,repairReason);
                // 修正必须能看到被拒绝的草稿及错误；不只重复原请求，也不执行任何部分意图。
                // 草稿仍是模型不可信数据，受长度与出站脱敏约束；只在当前一次修正中保留。
                String rejected=response==null?"":response.length()>24000?"[输出超过长度上限]":response;
                modelInput.put("previousAttempt",Map.of("output",com.example.report.operations.SensitiveData.text(rejected),"validationError",repairReason));
            }
        }
        var restored=new SemanticIntent(parsed.version(),parsed.action(),parsed.scopeChanges().stream()
                .map(c -> new SemanticIntent.ScopeChange(c.target(),c.operation(),c.mentions().stream().map(protectedInput::restore).toList(),
                        protectedInput.restore(c.evidence()),c.reportMentions().stream().map(protectedInput::restore).toList(),c.selectorKind(),c.quantifier(),c.conditions().stream().map(g -> new SemanticIntent.ConditionGroup(g.allOf().stream().map(f -> new SemanticIntent.FieldCondition(f.field(),f.operator(),f.values().stream().map(protectedInput::restore).toList(),protectedInput.restore(f.evidence()))).toList())).toList())).toList(),
                parsed.restrictions().stream().map(r -> new SemanticIntent.Restriction(r.action(),r.scope(),protectedInput.restore(r.evidence()))).toList(),
                parsed.reportConstraints().stream().map(r -> new SemanticIntent.ReportConstraint(protectedInput.restore(r.mention()),r.role(),protectedInput.restore(r.evidence()))).toList(),
                parsed.unsupportedConditions().stream().map(protectedInput::restore).toList(),parsed.clarify());
        codec.validate(restored,message);
        return restored;
    }
    @Override public Interpretation interpret(String message,Context context) {
        return new Interpretation(parse(message,context),props.getLlm().isMock()?Source.MOCK:Source.MODEL);
    }
    private static String normalize(String value) { return com.example.report.catalog.TextNormalizer.normalize(value); }
}
