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
        } catch (InvalidOutput failure) { throw new InvalidOutput(content,failure.reason()); }
        catch (Exception failure) { throw new InvalidOutput(content,"INVALID_STRUCTURE_OR_ACTION"); }
    }
    /** 要求每次修改和禁止都有本轮连续原文证据，并校验范围顺序、单家公司、修改数量及动作冲突。*/
    public void validate(SemanticIntent intent, String message) {
        // 统一任务入口直接调用validate，也必须得到与decode完全相同的字段诊断；否则模型只会反复提交同一错误。
        try { validateStructure(intent,message); }
        catch (InvalidOutput failure) { throw new InvalidOutput(failure.output(),diagnostics(intent,message,failure.reason())); }
    }
    /** 不改变任何意图字段；校验失败统一经validate补充具体证据路径后再交还规划器。 */
    private void validateStructure(SemanticIntent intent,String message) {
        if (intent == null || intent.version()!=SemanticIntent.VERSION || intent.action()==null || intent.clarify()==null
                || intent.scopeChanges()==null || intent.restrictions()==null || intent.reportConstraints()==null || intent.unsupportedConditions()==null
                || intent.scopeChanges().size()>8 || intent.restrictions().size()>7 || intent.reportConstraints().size()>20 || intent.unsupportedConditions().size()>20) throw invalid();
        boolean recordsStarted=false;
        int companies=0;
        for (var c : intent.scopeChanges()) {
            if (c==null || c.target()==null || c.operation()==null || c.operation()==Operation.KEEP
                    || c.mentions()==null || c.mentions().size()>20 || c.reportMentions()==null
                    || c.reportMentions().size()>20 || c.selectorKind()==null || c.quantifier()==null || c.conditions()==null || !grounded(message,c.evidence())) throw invalid();
            boolean clear=c.operation()==Operation.ALL_AUTHORIZED || c.operation()==Operation.RESTORE_ALL;
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
            } else if(!Set.of(Operation.REPLACE,Operation.ADD,Operation.REMOVE,Operation.ALL_AUTHORIZED).contains(c.operation())
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
                    || !Set.of(Operation.REPLACE,Operation.ALL_AUTHORIZED).contains(c.operation()))) throw invalid();
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
        Set<ForbiddenAction> forbidden=new HashSet<>();
        for (var r : intent.restrictions()) {
            if (r==null || r.forbiddenAction()==null || r.scope()!=RestrictionScope.THIS_TURN
                    || !grounded(message,r.evidence()) || !forbidden.add(r.forbiddenAction())) throw invalid();
        }
        if (intent.forbids(intent.action())) throw new InvalidOutput("","REQUESTED_ACTION_IS_IN_FORBIDDEN_SET");
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
        if(intent.action()!=null && intent.restrictions()!=null && intent.restrictions().stream().anyMatch(r->r!=null && r.forbiddenAction()!=null && r.forbiddenAction().name().equals(intent.action().name())))
            issues.add("当前action="+intent.action()+"同时出现在restrictions.forbiddenAction中。请区分用户实际禁止的动作与模型误编码：不提交/不执行使用仅存在于禁止集合的EXECUTE_DISPATCH，仍可准备清单；只预览不禁止PREVIEW。原文确实要求并禁止同一动作时才CLARIFY，不自动删除用户真正的禁止");
        for(var change:intent.scopeChanges()) {
            if(change==null)continue;
            String path="scopeChanges["+intent.scopeChanges().indexOf(change)+"]";
            if(!grounded(message,change.evidence()))issues.add(path+".evidence="+JsonUtil.toJson(change.evidence())+"不是本轮连续原文。scopeChanges仅描述本轮增量；历史已生效选择由当前预览保存，不能重新操作，也不能换一段本轮证据伪装成新授权；本轮未改变该选择时删除重复操作，保留当前目标与已保存选择");
            if(change.selectorKind()!=SelectorKind.REFERENCE && change.mentions()!=null)for(int index=0;index<change.mentions().size();index++) {
                String mention=change.mentions().get(index);
                if(!contains(change.evidence(),mention))issues.add(path+".mentions["+index+"]="+JsonUtil.toJson(mention)
                        +(contains(message,mention)?"在本轮出现但未被该项evidence覆盖，请引用包含对象的连续原文。"
                        :"未逐字出现在本轮，错误在mentions的值，不能通过扩写evidence补造原话。照抄本轮对象简称/别名，不补成目录标准全名；保留该对象限定，不能删掉它来通过校验。"));
            }
            if(change.target()==Target.COMPANY)issues.add("公司mentions复制原语言的公司原词或代码，不翻译、不补写称谓");
            if(change.target()==Target.REPORTS)issues.add("REPORTS只表达本轮范围变化：ADD追加具名报表，REMOVE移除具名报表，未改报表由当前范围保留；REPLACE仅用于本轮明确给出完整目标集合，不能补写未出现的报表名。ALL_AUTHORIZED才是全部授权报表且mentions=[]。剩余/其余等集合描述不是目录名称，不放mentions或reportConstraints");
            if(change.reportMentions()!=null)for(int index=0;index<change.reportMentions().size();index++) {
                String mention=change.reportMentions().get(index);
                if(!contains(message,mention))issues.add(path+".reportMentions["+index+"]="+JsonUtil.toJson(mention)+"未逐字出现在本轮；须改为本轮原有的报表简称/别名，不补标准全名、不引用历史。仅本轮未提报表时为空数组；用户给出的报表限定必须保留，不能清空限定而扩大到全部报表");
            }
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
        if(intent.restrictions()!=null)for(int i=0;i<intent.restrictions().size();i++) {
            var restriction=intent.restrictions().get(i);
            if(restriction!=null && !grounded(message,restriction.evidence()))issues.add("restrictions["+i+"].evidence="+JsonUtil.toJson(restriction.evidence())
                    +"不是本轮连续原文。THIS_TURN只声明本轮禁止动作，不能复制历史禁止语句；准备待确认清单本身不执行派单，无需为此前的禁止动作补造本轮证据");
        }
        if(intent.reportConstraints()!=null)for(int i=0;i<intent.reportConstraints().size();i++) {
            var reference=intent.reportConstraints().get(i);if(reference==null)continue;
            if(!grounded(message,reference.evidence()) || !contains(reference.evidence(),reference.mention()))
                issues.add("reportConstraints["+i+"].mention须逐字来自其evidence所引用的本轮原文，不得把别名补写为目录标准全名；evidence也不能取自历史。已有scopeChanges完整表达的范围无需重复reportConstraints");
        }
        return String.join("；",issues);
    }
    private static boolean contains(String source, String part) {
        return source != null && TextNormalizer.normalize(source).contains(TextNormalizer.normalize(part));
    }
    private static InvalidOutput invalid() { return new InvalidOutput("","INVALID_STRUCTURE_OR_EVIDENCE"); }
    /** 可修正的结构契约失败；向统一规划器提供约束原因，不暴露原始模型输出。 */
    public static final class InvalidOutput extends com.example.report.common.ModelContractViolation {
        private final String output;
        private final String reason;
        InvalidOutput(String output) { this(output,"MISSING_REPORT_ROLE"); }
        InvalidOutput(String output,String reason) { super("未能可靠识别本次操作，请明确要查询的公司、报表或派单动作",reason);this.output=output;this.reason=reason; }
        public String reason() { return reason; }
        String output() { return output; }
    }
}
