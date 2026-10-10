package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.semantic.DialogueState;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * 统一任务字段条件的证据校验；模型负责理解原文，程序核对新条件来源、旧条件变化及明确比较边界。
 * 不生成意图、不执行查询、不自动修改模型计划；无效复核必须在盲式上下文中修正，不能指使正确草稿改错。
 */
public final class TaskConditionEvidence {
    private TaskConditionEvidence() { }

    /**
     * 在冻结独立期望前校验所有实际条件；不读取业务数据库，不改变对话或选择状态。
     * @param message 本轮完整用户原文，所有新增条件及否定依据均须从这里引用
     * @param state 同一租约内的成功查询和对象引用，失败或过期历史不构成有效条件
     * @param context 本轮已授权候选的有限展示事实，用于核对真实可见字段值
     * @param review 模型独立产生的完整期望及条件依据；按字段、比较符和值匹配，不能缺少或添加不存在的条件
     * @throws ApiException 证据来源、覆盖或数值边界错误，返回422进入有界复核修正
     */
    public static void validate(String message,DialogueState state,AssistantPlanningContext context,SemanticReview review) {
        validate(message,state,context,review,Set.of(),Set.of());
    }

    /** 在已授权目录快照下比较实际范围；空报表/公司范围按当前授权解析，不把全部范围的两种表示误认为跨范围。 */
    public static void validate(String message,DialogueState state,AssistantPlanningContext context,SemanticReview review,
                                Set<String> authorizedReports,Set<String> authorizedCompanies) {
        var evidence=new LinkedHashMap<String,String>();
        for(int index=0;index<review.conditionChecks().size();index++)evidence.put("conditionChecks["+index+"].evidence",review.conditionChecks().get(index).evidence());
        for(int index=0;index<review.priorConditionChanges().size();index++)evidence.put("priorConditionChanges["+index+"].evidence",review.priorConditionChanges().get(index).evidence());
        CurrentTextEvidence.requireAll(message,evidence);
        var conditions=new LinkedHashMap<BusinessQuery.Filter,JsonNode>();
        conditions(review.expectedPlan()).values().forEach(node->conditions.put(filter(node),node));
        var seen=new HashSet<BusinessQuery.Filter>();
        if(conditions.isEmpty() && !review.conditionChecks().isEmpty())
            throw invalid("expectedPlan没有本轮字段条件，conditionChecks必须为[]；既有候选选择已经保存在预览中，不能把历史选择重新写成当前操作或条件证据");
        var previous=state.getBusinessQuery()==null?List.<JsonNode>of():conditions(new AssistantPlan(AssistantPlan.Route.BUSINESS_QUERY,state.getBusinessQuery(),false,null)).values();
        for(int index=0;index<review.conditionChecks().size();index++) {
            var check=review.conditionChecks().get(index);
            var key=filter(JsonUtil.MAPPER.valueToTree(check.condition()));var condition=conditions.get(key);
            if(condition==null || !seen.add(key))throw invalid("conditionChecks必须按field、operator、values绑定实际条件，不能添加不存在的条件或重复证据；请核对条件内容而不是数组顺序");
            if(!check.negationEvidence().isEmpty())CurrentTextEvidence.require(check.evidence(),check.negationEvidence(),"conditionChecks["+index+"].negationEvidence");
            if(review.expectedPlan().dispatch()!=null && !check.negationEvidence().isEmpty())
                throw invalid("候选条件描述被操作集合，EXCLUDE/RESTORE不能在negationEvidence中再次取反");
            switch(check.origin()) {
                case ACTIVE_QUERY -> {
                    if(!review.expectedPlan().followUp() || state.isBusinessUnresolved() || previous.stream().noneMatch(old->same(old,condition)))
                        throw invalid("ACTIVE_QUERY只能继承previousQuery中仍然存在的完整条件；本轮新改的比较用CURRENT_REQUEST，已撤销条件不能从recentConversation恢复");
                    if(!check.negationEvidence().isEmpty())throw invalid("继承条件不能同时取反；本轮修改应声明CURRENT_REQUEST");
                }
                case VISIBLE_OBJECT -> {
                    var visible=new ArrayList<JsonNode>();
                    if(review.expectedPlan().query()!=null && !state.isBusinessUnresolved())visible.add(JsonUtil.MAPPER.valueToTree(state.getBusinessReferences()));
                    if(review.expectedPlan().dispatch()!=null) {
                        visible.add(JsonUtil.MAPPER.valueToTree(context.dispatchSelection()));
                        visible.add(JsonUtil.MAPPER.valueToTree(state.getLastSelectionReferences()));
                    }
                    String field=condition.path("field").asText();
                    if(condition.path("values").isEmpty() || java.util.stream.StreamSupport.stream(condition.path("values").spliterator(),false)
                            .anyMatch(value->visible.stream().noneMatch(node->containsValue(node,field,value.asText()))))
                        throw invalid("VISIBLE_OBJECT的条件值必须来自已展示的同名字段，不能补造对象或读取未展示历史");
                    if(!check.negationEvidence().isEmpty())throw invalid("对象引用的取反应在本轮CURRENT_REQUEST中说明");
                }
                case CURRENT_REQUEST -> NumericBoundaryEvidence.validate(condition.path("operator").asText(),condition.path("values"),check.evidence(),check.negationEvidence());
            }
        }
        if(!seen.equals(conditions.keySet()))throw invalid("conditionChecks必须覆盖所有不同的实际字段条件；按field、operator、values绑定，不按数组下标或出现顺序绑定");
        validatePriorChanges(message,state,review,conditions.keySet(),authorizedReports,authorizedCompanies);
    }

    /**
     * 逐项核对同范围追问中消失的旧谓词，防止同字段另一端点仍存在时掩盖范围扩大。
     * 新查询、跨范围查询、按稳定身份核验及非查询没有这类继承关系，必须显式给空数组；不从历史重建条件。
     */
    private static void validatePriorChanges(String message,DialogueState state,SemanticReview review,Set<BusinessQuery.Filter> retained,
                                             Set<String> authorizedReports,Set<String> authorizedCompanies) {
        var plan=review.expectedPlan();var before=state.getBusinessQuery();var after=plan.query();
        boolean comparable=plan.followUp() && before!=null && !state.isBusinessUnresolved() && after!=null
                && after.view()!=BusinessQuery.View.ELIGIBILITY && QueryScope.same(before,after,authorizedReports,authorizedCompanies);
        var missing=new LinkedHashSet<BusinessQuery.Filter>();
        if(comparable)before.conditions().forEach(group->group.allOf().forEach(condition->missing.add(filter(JsonUtil.MAPPER.valueToTree(condition)))));
        var actualPrevious=Set.copyOf(missing);
        missing.removeAll(retained);
        var explained=new HashSet<BusinessQuery.Filter>();
        for(int index=0;index<review.priorConditionChanges().size();index++) {
            var change=review.priorConditionChanges().get(index);
            var old=filter(JsonUtil.MAPPER.valueToTree(change.condition()));
            if(!missing.contains(old) || !explained.add(old))throw new com.example.report.common.ModelContractViolation("旧条件变更依据与当前查询不一致",
                    "priorConditionChanges["+index+"].condition="+JsonUtil.toJson(old)+"未对应本轮需说明的旧条件。当前可继承旧条件="+JsonUtil.toJson(actualPrevious)
                            +"；本轮被替换或删除的旧条件="+JsonUtil.toJson(missing)
                            +"。只按上述当前事实填写，不能从recentConversation补造更早比较符；缺失集合为[]时priorConditionChanges必须为[]。重复声明相同阈值不会产生旧条件变化；独立查询、跨范围查询和资格核验也不填写此数组");
            NumericBoundaryEvidence.validateChangeEvidence(old.operator(),change.evidence());
        }
        missing.removeAll(explained);
        if(!missing.isEmpty())throw invalid("priorConditionChanges遗漏了被替换或删除的旧条件："+JsonUtil.toJson(missing)
                +"。逐项保留仍有效条件，或说明本轮授权的变更范围和原文；同一字段的上限与下限是两个条件，只修改上限不得删除下限，反之亦然。不能因字段仍在就忽略旧端点");
    }

    /** 提取稳定条件身份；字段证据文本和数组位置不参与身份，AND/OR分组仍由完整计划比较独立核对。 */
    static BusinessQuery.Filter filter(JsonNode node) {
        var values=java.util.stream.StreamSupport.stream(node.path("values").spliterator(),false).map(JsonNode::asText).toList();
        if(Set.of("IN","NOT_IN").contains(node.path("operator").asText()))values=List.copyOf(new TreeSet<>(values));
        // 标量比较的12.50与12.5是同一阈值；身份和文本EQ仍保留原值，避免把编号前导零抹掉。
        if(Set.of("GT","GTE","LT","LTE").contains(node.path("operator").asText()))values=values.stream().map(value->{
            var number=NumericBoundaryEvidence.decimal(value);return number==null?value:number.stripTrailingZeros().toPlainString();
        }).toList();
        return new BusinessQuery.Filter(node.path("field").asText(),node.path("operator").asText(),values);
    }

    /** 只枚举协议内的字段条件，不遍历任意JSON结构或将其他对象误作条件。 */
    static Map<String,JsonNode> conditions(AssistantPlan plan) {
        var result=new LinkedHashMap<String,JsonNode>();var root=JsonUtil.MAPPER.valueToTree(plan);
        collect(root.path("query").path("conditions"),"/query/conditions",result);
        var changes=root.path("dispatch").path("intent").path("scopeChanges");
        for(int i=0;i<changes.size();i++)collect(changes.get(i).path("conditions"),"/dispatch/intent/scopeChanges/"+i+"/conditions",result);
        return result;
    }
    private static void collect(JsonNode groups,String prefix,Map<String,JsonNode> result) {
        for(int i=0;i<groups.size();i++) {
            var filters=groups.get(i).path("allOf");
            for(int j=0;j<filters.size();j++)result.put(prefix+"/"+i+"/allOf/"+j,filters.get(j));
        }
    }
    /** 继承须匹配完整的已生效条件；不把旧历史中的近义表达或失效条件混入当前查询。 */
    private static boolean same(JsonNode left,JsonNode right) {
        return filter(left).equals(filter(right));
    }
    private static boolean containsValue(JsonNode node,String field,String expected) {
        if(node.isObject() && node.path(field).isValueNode() && !node.path(field).isNull() && node.path(field).asText().equals(expected))return true;
        if(node.isContainerNode())for(var child:node)if(containsValue(child,field,expected))return true;
        return false;
    }
    private static ApiException invalid(String message){return new ApiException(422,message);}
}
