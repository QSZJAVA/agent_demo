package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.common.ModelContractViolation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/**
 * 统一任务的确定性语义比较；只消除集合顺序与证据引用措辞的表示差异，不以结果相同替代查询条件相同。
 * 模型期望只形成有依据的修正反馈，不能直接调用业务、扩大权限或绕过后续规划与预检。
 */
public final class TaskPlanComparison {
    private TaskPlanComparison() { }

    /**
     * 核对复核原文依据并比较完整计划；每个不同维度都必须有本轮原文支持，否则属于无效复核。
     * @param message 本轮原始要求，保持脱敏占位恢复后的文本
     * @param actual 已通过程序与只读预检的草稿
     * @param review 独立模型给出的要求及完整期望
     * @return 可直接供规划器阅读的结构化差异；空集合才表示通过
     * @throws ApiException 复核依据不属于本轮或无法支持实际差异，不能将此类错误归咎于草稿
     */
    public static List<Difference> compare(String message,AssistantPlan actual,SemanticReview review) {
        validateEvidence(message,review);
        var before=canonical(actual);var after=canonical(review.expectedPlan());
        var differences=new ArrayList<Difference>();
        var failures=new LinkedHashSet<String>();
        compareNode("",before,after,review.requirements(),differences,failures);
        if(!failures.isEmpty())throw new ModelContractViolation("语义复核缺少差异维度依据",String.join("；",failures));
        return List.copyOf(differences);
    }
    /**
     * 独立期望中的条件必须先有条件维度的本轮要求，不能等到与草稿比较后才补摘要。
     * @param review 模型自行形成的期望；只读取该对象，不读取规划草稿或替模型补写依据
     * @throws ModelContractViolation 字段条件、记录选择或旧条件撤销没有CONDITIONS依据，须与其他独立错误一并修正
     */
    public static void validateRequirementCoverage(SemanticReview review) {
        var plan=review.expectedPlan();
        boolean hasConditions=!TaskConditionEvidence.conditions(plan).isEmpty()
                || !plan.removedFilters().isEmpty() || !review.priorConditionChanges().isEmpty()
                || plan.dispatch()!=null && plan.dispatch().intent().scopeChanges().stream()
                    .anyMatch(change->change.target()==com.example.report.semantic.SemanticIntent.Target.RECORDS);
        // 继承条件和可见对象身份仍影响目标集合；conditionChecks解释每个条件的来源，不能替代要求维度。
        if(hasConditions && review.requirements().stream().noneMatch(requirement->requirement.aspect()==SemanticReview.Aspect.CONDITIONS))
            throw new ModelContractViolation("语义复核缺少条件要求",
                    "requirements缺少CONDITIONS维度的本轮依据。expectedPlan包含字段条件、记录选择或旧条件撤销时，"
                    +"必须在该维度解释全部仍有效限定；继承ACTIVE_QUERY或引用VISIBLE_OBJECT也引用本轮承接/指代原话。"
                    +"conditionChecks及priorConditionChanges不能替代requirements；补齐依据时保留已正确的旧条件、身份和比较关系，不得删掉条件绕过覆盖校验");
    }
    /** 独立期望只以本轮原话作为要求证据；历史可解释含义，不能授权新操作或替代当前指代短语。 */
    public static void validateEvidence(String message,SemanticReview review) {
        var evidence=new LinkedHashMap<String,String>();
        for(int index=0;index<review.requirements().size();index++)
            evidence.put("requirements["+index+"].evidence",review.requirements().get(index).evidence());
        if(review.targetCount()!=null)evidence.put("targetCount.evidence",review.targetCount().evidence());
        CurrentTextEvidence.requireAll(message,evidence);
    }
    /**
     * 将用户指定总数与完整只读选择事实比较；事实未知或数量不符必须澄清，不能缩小清单或自行恢复额外条目。
     * @param review 已验证来源的独立期望
     * @param facts 同一会话租约内对该期望取得的预检事实，不读取未授权对象
     */
    public static void validateTargetCount(SemanticReview review,Map<String,Object> facts) {
        validateTargetCount(review.targetCount(),review.expectedPlan(),facts);
    }
    /** 独立目标的总数直接核对事实；详细复核漏填时也须一次反馈实际数量差异，不能多耗一轮才发现不足。 */
    public static void validateTargetCount(SemanticReview.TargetCount targetCount,AssistantPlan plan,Map<String,Object> facts) {
        if(targetCount==null || plan.route()==AssistantPlan.Route.CLARIFY)return;
        if(plan.dispatch()==null)throw new ApiException(422,"targetCount只约束派单目标，普通查询不填该字段");
        var evidence=JsonUtil.MAPPER.valueToTree(facts);
        var count=evidence.has("targetCount")?evidence.path("targetCount"):evidence.has("itemCount")?evidence.path("itemCount"):evidence.at("/selectionAfter/selectedCount");
        if(!count.isIntegralNumber() || count.asInt()!=targetCount.count())
            throw new ApiException(422,"用户要求最终 "+targetCount.count()+" 条，但当前完整预检为 "+(count.isIntegralNumber()?count.asInt():"未知")+" 条；未完成的选择不能当作成功，请澄清对象或先由用户完成选择，不得自行增加、减少目标或删除数量要求");
    }
    /** 比较对象字段；数组作为完整逻辑单元比较，避免将AND/OR、完整目标集合拆成可任意应用的补丁。 */
    private static void compareNode(String path,JsonNode before,JsonNode after,List<SemanticReview.Requirement> requirements,List<Difference> differences,Set<String> failures) {
        if(before.equals(after))return;
        if(before.isObject() && after.isObject()) {
            var names=new TreeSet<String>();before.fieldNames().forEachRemaining(names::add);after.fieldNames().forEachRemaining(names::add);
            for(String name:names)compareNode(path+"/"+name,before.path(name),after.path(name),requirements,differences,failures);
            return;
        }
        for(var aspect:aspects(path,before,after)) {
            var evidence=requirements.stream().filter(r->r.aspect()==aspect && r.messageIndex()==-1).map(SemanticReview.Requirement::evidence).distinct().toList();
            // 路径之间相互独立，一次列全缺失维度；任何一项无依据都不返回可供规划器采用的差异。
            if(evidence.isEmpty())failures.add("语义复核对"+path+"的期望变化缺少"+aspect+"维度的本轮依据");
            else differences.add(new Difference(path,aspect,before,after,evidence));
        }
    }

    /** 公司/报表范围与记录选择属于不同维度；混合操作仍整组比较，不能将数组拆成可执行补丁。 */
    private static List<SemanticReview.Aspect> aspects(String path,JsonNode before,JsonNode after) {
        if(!path.equals("/dispatch/intent/scopeChanges") || !before.isArray() || !after.isArray())return List.of(aspect(path));
        var changed=new ArrayList<SemanticReview.Aspect>();
        for(boolean records:List.of(false,true)) {
            var left=JsonUtil.MAPPER.createArrayNode();var right=JsonUtil.MAPPER.createArrayNode();
            for(var node:before)if(records=="RECORDS".equals(node.path("target").asText()))left.add(node);
            for(var node:after)if(records=="RECORDS".equals(node.path("target").asText()))right.add(node);
            if(!left.equals(right))changed.add(records?SemanticReview.Aspect.CONDITIONS:SemanticReview.Aspect.SCOPE);
        }
        // 只有跨类别顺序不同仍须记录差异；协议预检另拒绝先记录、后范围的非法顺序。
        return changed.isEmpty()?List.of(SemanticReview.Aspect.CONDITIONS):List.copyOf(changed);
    }

    /** 维度由协议字段决定，不采信模型将范围或动作变化归类成无关的展示修正。 */
    private static SemanticReview.Aspect aspect(String path) {
        if(path.equals("/route") || path.startsWith("/dispatch/intent/action") || path.startsWith("/dispatch/intent/restrictions"))return SemanticReview.Aspect.ACTION;
        if(path.equals("/followUp") || path.startsWith("/removedFilters"))return SemanticReview.Aspect.CONTINUITY;
        if(path.equals("/clarification") || path.startsWith("/dispatch/intent/clarify") || path.startsWith("/dispatch/intent/unsupportedConditions"))return SemanticReview.Aspect.CAPABILITY;
        if(path.startsWith("/query/conditions") || path.startsWith("/dispatch/intent/scopeChanges")
                || path.startsWith("/dispatch/intent/reportConstraints"))return SemanticReview.Aspect.CONDITIONS;
        if(path.startsWith("/dispatch/source") || path.startsWith("/dispatch/referenceKeys"))return SemanticReview.Aspect.REFERENCE;
        if(path.equals("/query/domain") || path.equals("/query/reportIds") || path.equals("/query/companyCode"))return SemanticReview.Aspect.SCOPE;
        if(path.startsWith("/query/"))return SemanticReview.Aspect.PRESENTATION;
        // 路由整体改变时query/dispatch同时出现或消失；这属于动作更换，而非字段或来源的小修正。
        return SemanticReview.Aspect.ACTION;
    }

    /**
     * 仅规范化协议明确为集合的字段。数值字符串、比较算子、分页及单据身份保持原值，不能依赖当前结果巧合放宽。
     * evidence为证据引用而非行为；清单选择操作的执行顺序、条件组边界和量词仍完整保留。
     */
    private static JsonNode canonical(AssistantPlan plan) {
        var root=(ObjectNode)JsonUtil.MAPPER.valueToTree(plan);
        // 澄清文本没有执行含义；行为一致时采用独立复核认可的表达，不把同义改写当成更换业务任务。
        root.remove("clarification");
        normalize(root,"");return root;
    }
    private static void normalize(JsonNode node,String path) {
        if(node.isObject()) {
            var object=(ObjectNode)node;object.remove("evidence");
            var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);
            for(String name:names)normalize(object.get(name),path+"/"+name);
        } else if(node.isArray()) {
            var array=(ArrayNode)node;for(var child:array)normalize(child,path+"/*");
            if(path.endsWith("/reportIds") || path.endsWith("/referenceKeys") || path.equals("/removedFilters")
                    || path.equals("/query/conditions") || path.equals("/query/conditions/*/allOf") || path.equals("/query/conditions/*/allOf/*/values")) {
                var values=new TreeMap<String,JsonNode>();for(var child:array)values.put(child.toString(),child);
                array.removeAll();values.values().forEach(array::add);
            }
        }
    }

    /**
     * 一处经程序确认的计划差异；只作为下一次完整规划的输入，不对当前计划就地应用。
     * @param path 稳定JSON字段路径，数组差异代表该完整逻辑集合
     * @param aspect 由服务端路径确定的语义维度
     * @param actual 当前草稿中的原始行为值
     * @param expected 独立复核期望的行为值
     * @param evidence 支持该维度的本轮连续原文，至少一项
     */
    public record Difference(String path,SemanticReview.Aspect aspect,JsonNode actual,JsonNode expected,List<String> evidence) { }
}
