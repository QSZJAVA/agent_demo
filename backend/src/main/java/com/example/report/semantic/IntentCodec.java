package com.example.report.semantic;

import com.example.report.catalog.TextNormalizer;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;

/**
 * 真实模型输出的协议与原文证据校验边界；拒绝未知字段、缺失字段、隐式类型转换及多余JSON。
 * 只检验结构、实体角色和业务不变量，不用关键词构造意图；只接受当前V1协议。
 */
@Component
public class IntentCodec {
    private final ObjectMapper mapper = JsonUtil.MAPPER.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    public String schema() {
        try (var in = new ClassPathResource("semantic/intent-v1.schema.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("语义协议加载失败", error); }
    }
    /** 解析有界JSON并校验意图；任何解析或协议失败都返回 InvalidOutput，交由模型格式修复或对话澄清处理。 */
    public SemanticIntent decode(String json, String message) {
        if (json == null || json.length() > 24000) throw invalid();
        String content = json.trim();
        if (content.startsWith("```json") && content.endsWith("```")) content = content.substring(7, content.length()-3).trim();
        SemanticIntent intent=null;
        try {
            intent = mapper.readValue(content, SemanticIntent.class);
            validate(intent, message);
            return intent;
        } catch (InvalidOutput failure) { throw new InvalidOutput(content,diagnostics(intent,message,failure.reason())); }
        catch (Exception failure) { throw new InvalidOutput(content,"INVALID_STRUCTURE_OR_ACTION"); }
    }
    /** 要求每次修改和禁止都有本轮连续原文证据，并校验范围顺序、单家公司、修改数量及动作冲突。*/
    public void validate(SemanticIntent intent, String message) {
        if (intent == null || intent.version()!=SemanticIntent.VERSION || intent.action()==null || intent.clarify()==null
                || intent.scopeChanges()==null || intent.restrictions()==null || intent.reportConstraints()==null || intent.unsupportedConditions()==null
                || intent.scopeChanges().size()>8 || intent.restrictions().size()>7 || intent.reportConstraints().size()>20 || intent.unsupportedConditions().size()>20) throw invalid();
        boolean recordsStarted=false;
        int companies=0;
        for (var c : intent.scopeChanges()) {
            if (c==null || c.target()==null || c.operation()==null || c.operation()==Operation.KEEP
                    || c.mentions()==null || c.mentions().size()>20 || c.reportMentions()==null
                    || c.reportMentions().size()>20 || c.selectorKind()==null || c.quantifier()==null || c.conditions()==null || !grounded(message,c.evidence())) throw invalid();
            boolean clear=c.operation()==Operation.CLEAR || c.operation()==Operation.RESTORE_ALL;
            boolean emptyMentions=clear || c.selectorKind()==SelectorKind.FIELDS || c.selectorKind()==SelectorKind.ALL;
            // 缺少实体与实体放错字段需要相反的修正，不能用同一条“必须为空”反馈让模型反复删掉必填实体。
            if(emptyMentions && !c.mentions().isEmpty())
                throw new InvalidOutput("","SELECTOR_MENTIONS_MUST_BE_EMPTY：当前target="+c.target()+"，operation="+c.operation()+"，selectorKind="+c.selectorKind()
                        +"的mentions必须为空。FIELDS值放conditions；ALL记录操作的报表名只放reportMentions；恢复全部不填记录名称。");
            if(!emptyMentions && c.mentions().isEmpty())
                throw new InvalidOutput("","SELECTOR_MENTIONS_REQUIRED：当前target="+c.target()+"，operation="+c.operation()+"，selectorKind="+c.selectorKind()
                        +"的mentions不能为空。公司/报表范围操作填写本轮实体原词；记录定位填写单据、摘要、客户原词或既有REFERENCE键。若原意是恢复某报表全部勾选，使用RECORDS RESTORE、selectorKind=ALL、quantifier=ALL，报表原词放reportMentions，mentions为空。");
            if(c.target()==Target.RECORDS) {
                if(!Set.of(Operation.EXCLUDE,Operation.RESTORE,Operation.REPLACE_EXCLUSIONS,Operation.RESTORE_ALL,Operation.KEEP_ONLY).contains(c.operation())) throw new InvalidOutput("","RECORD_OPERATION_MUST_BE_EXCLUDE_OR_RESTORE");
                if(clear != (c.selectorKind()==SelectorKind.NONE)) throw invalid();
                if(c.selectorKind()==SelectorKind.ALL && (c.quantifier()!=Quantifier.ALL || !Set.of(Operation.EXCLUDE,Operation.RESTORE).contains(c.operation())))
                    throw new InvalidOutput("","ALL_SELECTOR_REQUIRES_EXCLUDE_OR_RESTORE_WITH_ALL_QUANTIFIER");
                if(c.selectorKind()==SelectorKind.REFERENCE && !Set.of(Operation.EXCLUDE,Operation.RESTORE,Operation.REPLACE_EXCLUSIONS,Operation.KEEP_ONLY).contains(c.operation()))throw invalid();
            } else if(!Set.of(Operation.REPLACE,Operation.ADD,Operation.REMOVE,Operation.CLEAR).contains(c.operation())
                    || c.selectorKind()!=SelectorKind.NONE || c.quantifier()!=Quantifier.UNSPECIFIED) throw invalid();
            if(c.operation()==Operation.KEEP_ONLY && Set.of(SelectorKind.NONE,SelectorKind.ALL).contains(c.selectorKind()))throw invalid();
            if(c.selectorKind()==SelectorKind.FIELDS) {
                if(c.conditions().isEmpty() || c.conditions().size()>4) throw invalid();
                for(var group:c.conditions()) {
                    if(group==null || group.allOf()==null || group.allOf().isEmpty() || group.allOf().size()>8) throw invalid();
                    for(var condition:group.allOf()) {
                        if(condition==null || condition.field()==null || !condition.field().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                                || condition.operator()==null || condition.values()==null || condition.values().size()>20
                                || !grounded(c.evidence(),condition.evidence()) || condition.values().stream().anyMatch(v -> v==null || v.length()>4096)) throw invalid();
                        int n=condition.values().size();
                        if(switch(condition.operator()) {case IS_NULL,NOT_NULL -> n!=0;case IN,NOT_IN -> n==0;default -> n!=1;}) throw invalid();
                    }
                }
            } else if(!c.conditions().isEmpty()) throw invalid();
            for (String mention : c.mentions())
                if (mention==null || mention.isBlank() || mention.length()>160
                        || (c.selectorKind()==SelectorKind.REFERENCE ? !mention.matches("ref_[a-f0-9]{32}") : !contains(c.evidence(),mention))) throw invalid();
            // 报表限定可来自本轮前一分句；与默认引用整轮evidence等价，仍不能引用历史或改写查询范围。
            if (c.target()!=Target.RECORDS && !c.reportMentions().isEmpty()) throw new InvalidOutput("","NON_RECORD_TARGET_REQUIRES_EMPTY_REPORT_MENTIONS");
            for (String report : c.reportMentions())
                if (report==null || report.isBlank() || report.length()>160 || !contains(message,report)) throw invalid();
            if (c.target()==Target.COMPANY && (++companies>1 || c.mentions().size()>1
                    || !Set.of(Operation.REPLACE,Operation.CLEAR).contains(c.operation()))) throw invalid();
            if (c.target()==Target.RECORDS) recordsStarted=true;
            else if (recordsStarted) throw invalid();
        }
        for(var reference:intent.reportConstraints()) {
            if(reference==null || reference.role()==null || reference.mention()==null || reference.mention().isBlank()
                    || reference.mention().length()>160 || !grounded(message,reference.evidence())
                    || !contains(reference.evidence(),reference.mention())) throw new InvalidOutput("","UNGROUNDED_REPORT_ROLE");
            if(intent.action()!=Action.CLARIFY && reference.role()==ReportRole.RECORD_SCOPE && intent.scopeChanges().stream().noneMatch(c -> c.target()==Target.RECORDS
                    && c.reportMentions().stream().anyMatch(m -> contains(m,reference.mention()) || contains(reference.mention(),m))))
                throw new InvalidOutput("","RECORD_SCOPE_REQUIRES_RECORD_SELECTOR：RECORD_SCOPE仅限定实际记录选择操作所属的报表。产品、费用类型或已选记录描述不是报表；仅要求准备当前已选记录时无需RECORDS或RECORD_SCOPE，保留PREPARE_DISPATCH及原有选择，不得为满足此约束新增修改。");
        }
        // 同一原句可以同时表达范围和记录条件；是否确有两种要求由统一语义复核判断，不能按证据重叠否决。
        for(String condition:intent.unsupportedConditions()) if(!grounded(message,condition)) throw new InvalidOutput("","UNSUPPORTED_CONDITIONS_MUST_BE_VERBATIM_INPUT");
        if(!intent.unsupportedConditions().isEmpty() && intent.action()!=Action.CLARIFY)
            throw new InvalidOutput("","UNSUPPORTED_CONDITION_REQUIRES_CLARIFY");
        Set<Action> forbidden=new HashSet<>();
        for (var r : intent.restrictions()) {
            if (r==null || r.action()==null || r.action()==Action.CLARIFY || r.scope()!=RestrictionScope.THIS_TURN
                    || !grounded(message,r.evidence()) || !forbidden.add(r.action())) throw invalid();
        }
        if (intent.forbids(intent.action())) throw new InvalidOutput("","ACTION_CONFLICT_REQUIRES_CLARIFY_ACTION_WITH_EMPTY_CHANGES");
        if ((intent.action()==Action.CLARIFY)!=(intent.clarify()!=Clarify.NONE)) throw new InvalidOutput("","NON_CLARIFY_ACTION_REQUIRES_CLARIFY_NONE");
        // 澄清可以保留已经识别的候选操作供审计；Planner.requireAction会在任何归并之前终止，不执行候选。
        if (Set.of(Action.HELP,Action.CANCEL_PLAN,Action.SHOW_RESULT).contains(intent.action())
                && !intent.scopeChanges().isEmpty()) throw new InvalidOutput("","NON_QUERY_OR_CLARIFY_REQUIRES_EMPTY_SCOPE_CHANGES");
    }
    private static boolean grounded(String source,String evidence) {
        return evidence!=null && !evidence.isBlank() && evidence.length()<=1000 && contains(source,evidence);
    }
    /** 汇总独立的证据错误供有界模型修正；只解释协议约束，不代填意图、不执行部分修改。 */
    private static String diagnostics(SemanticIntent intent,String message,String first) {
        if(intent==null || intent.scopeChanges()==null)return first;
        var issues=new LinkedHashSet<String>();issues.add(first);
        for(var change:intent.scopeChanges()) {
            if(change==null)continue;
            if(!grounded(message,change.evidence()))issues.add("操作evidence须逐字引用本轮连续原文，不得补词或改写");
            if(change.selectorKind()!=SelectorKind.REFERENCE && change.mentions()!=null && change.mentions().stream().anyMatch(m->!contains(change.evidence(),m)))
                issues.add("mentions中的对象必须出现在该操作的evidence中；引用包含对象的连续原文，不能只引用动作");
            if(change.reportMentions()!=null && change.reportMentions().stream().anyMatch(m->!contains(message,m)))
                issues.add("reportMentions只能使用本轮原文出现的报表，不得补标准名或历史名称");
            if(change.conditions()!=null)for(var group:change.conditions())if(group!=null && group.allOf()!=null)
                for(var field:group.allOf())if(field!=null && !grounded(change.evidence(),field.evidence()))
                    issues.add("条件evidence须逐字引用所属操作evidence中的连续片段；不可补省略的字段名或把区间改写成比较句；同一原文片段可支持多个条件");
        }
        if(intent.unsupportedConditions()!=null) {
            if(intent.unsupportedConditions().stream().anyMatch(c->!grounded(message,c)))
                issues.add("unsupportedConditions只能逐字引用不支持的条件，不能添加原因、冒号解释或虚构字段");
            if(!intent.unsupportedConditions().isEmpty() && intent.action()!=Action.CLARIFY)
                issues.add("存在不支持条件时action必须为CLARIFY且clarify不能为NONE；整轮不能部分执行");
        }
        return String.join("；",issues);
    }
    private static boolean contains(String source, String part) {
        return source != null && TextNormalizer.normalize(source).contains(TextNormalizer.normalize(part));
    }
    private static InvalidOutput invalid() { return new InvalidOutput("","INVALID_STRUCTURE_OR_EVIDENCE"); }
    /** 可修正的结构契约失败；向统一规划器提供约束原因，不暴露原始模型输出。 */
    public static final class InvalidOutput extends ApiException {
        private final String output;
        private final String reason;
        InvalidOutput(String output) { this(output,"MISSING_REPORT_ROLE"); }
        InvalidOutput(String output,String reason) { super(422,"未能可靠识别本次操作，请明确要查询的公司、报表或派单动作");this.output=output;this.reason=reason; }
        public String reason() { return reason; }
        String output() { return output; }
    }
}
