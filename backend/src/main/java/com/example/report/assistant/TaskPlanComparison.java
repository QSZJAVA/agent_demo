package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
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
     * @param history 实际发送给模型的有界对话快照，按0起下标验证引用，不从外部再加载历史
     * @param actual 已通过程序与只读预检的草稿
     * @param review 独立模型给出的要求及完整期望
     * @return 可直接供规划器阅读的结构化差异；空集合才表示通过
     * @throws ApiException 复核依据不属于本轮或无法支持实际差异，不能将此类错误归咎于草稿
     */
    public static List<Difference> compare(String message,List<Map<String,String>> history,AssistantPlan actual,SemanticReview review) {
        validateEvidence(message,history,review);
        var before=canonical(actual);var after=canonical(review.expectedPlan());
        var differences=new ArrayList<Difference>();
        compareNode("",before,after,review.requirements(),differences);
        return List.copyOf(differences);
    }
    /** 独立期望必须先通过来源核对；显式索引由服务端提供，错误引用不能被模糊匹配到另一条消息。 */
    public static void validateEvidence(String message,List<Map<String,String>> history,SemanticReview review) {
        for(var requirement:review.requirements()) {
            int index=requirement.messageIndex();
            String source=index<0?message:index<history.size()?history.get(index).getOrDefault("content",""):"";
            if(!source.contains(requirement.evidence()))throw new ApiException(422,"语义复核依据与messageIndex="+index+"指向的实际对话不符");
        }
        if(review.requirements().stream().noneMatch(r->r.messageIndex()==-1))throw new ApiException(422,"语义复核必须包含本轮消息的要求，历史不能单独授权操作");
        if(review.targetCount()!=null && !message.contains(review.targetCount().evidence()))
            throw new ApiException(422,"目标总条数必须引用本轮连续原文");
    }
    /**
     * 将用户指定总数与完整只读选择事实比较；事实未知或数量不符必须澄清，不能缩小清单或自行恢复额外条目。
     * @param review 已验证来源的独立期望
     * @param facts 同一会话租约内对该期望取得的预检事实，不读取未授权对象
     */
    public static void validateTargetCount(SemanticReview review,Map<String,Object> facts) {
        if(review.targetCount()==null || review.expectedPlan().route()==AssistantPlan.Route.CLARIFY)return;
        if(review.expectedPlan().dispatch()==null)throw new ApiException(422,"targetCount只约束派单目标，普通查询不填该字段");
        var evidence=JsonUtil.MAPPER.valueToTree(facts);
        var count=evidence.has("targetCount")?evidence.path("targetCount"):evidence.has("itemCount")?evidence.path("itemCount"):evidence.at("/selectionAfter/selectedCount");
        if(!count.isIntegralNumber() || count.asInt()!=review.targetCount().count())
            throw new ApiException(422,"用户要求最终 "+review.targetCount().count()+" 条，但当前完整预检为 "+(count.isIntegralNumber()?count.asInt():"未知")+" 条；未完成的选择不能当作成功，请澄清对象或先由用户完成选择，不得自行增加、减少目标或删除数量要求");
    }
    /** 无历史的内部或程序测试调用，仍执行同一证据验证。 */
    public static List<Difference> compare(String message,AssistantPlan actual,SemanticReview review){return compare(message,List.of(),actual,review);}

    /** 比较对象字段；数组作为完整逻辑单元比较，避免将AND/OR、完整目标集合拆成可任意应用的补丁。 */
    private static void compareNode(String path,JsonNode before,JsonNode after,List<SemanticReview.Requirement> requirements,List<Difference> differences) {
        if(before.equals(after))return;
        if(before.isObject() && after.isObject()) {
            var names=new TreeSet<String>();before.fieldNames().forEachRemaining(names::add);after.fieldNames().forEachRemaining(names::add);
            for(String name:names)compareNode(path+"/"+name,before.path(name),after.path(name),requirements,differences);
            return;
        }
        var aspect=aspect(path);
        var evidence=requirements.stream().filter(r->r.aspect()==aspect && r.messageIndex()==-1).map(SemanticReview.Requirement::evidence).distinct().toList();
        if(evidence.isEmpty())throw new ApiException(422,"语义复核对"+path+"的期望变化缺少"+aspect+"维度的本轮依据");
        differences.add(new Difference(path,aspect,before,after,evidence));
    }

    /** 维度由协议字段决定，不采信模型将范围或动作变化归类成无关的展示修正。 */
    private static SemanticReview.Aspect aspect(String path) {
        if(path.equals("/route") || path.startsWith("/dispatch/intent/action"))return SemanticReview.Aspect.ACTION;
        if(path.equals("/followUp") || path.startsWith("/removedFilters"))return SemanticReview.Aspect.CONTINUITY;
        if(path.equals("/clarification") || path.startsWith("/dispatch/intent/clarify") || path.startsWith("/dispatch/intent/unsupportedConditions"))return SemanticReview.Aspect.CAPABILITY;
        if(path.startsWith("/query/conditions") || path.startsWith("/dispatch/intent/scopeChanges") || path.startsWith("/dispatch/intent/restrictions")
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
