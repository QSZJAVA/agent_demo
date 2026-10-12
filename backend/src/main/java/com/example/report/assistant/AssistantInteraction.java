package com.example.report.assistant;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import com.example.report.permission.CurrentUser;
import com.example.report.semantic.DialogueState;
import java.util.*;
import com.fasterxml.jackson.databind.*;

/** 结构化编辑与澄清回复的当前版本保护；仅生成用户明确选择的本轮输入，仍进入真实模型规划及独立复核。 */
public final class AssistantInteraction {
    private AssistantInteraction() { }
    private static final ObjectMapper STRICT=JsonUtil.strictMapper();
    /** 严格读取当前结构化请求，拒绝数值转字符串及额外字段；null仅表示未使用该交互入口。 */
    public static <T> T decode(Object value,Class<T> type){
        if(value==null)return null;
        if(type!=QueryEdit.class && type!=ClarificationReply.class)throw new IllegalArgumentException("未声明的交互请求类型");
        try{String json=JsonUtil.toJson(value);if(json.length()>96000)throw new IllegalArgumentException();return STRICT.readValue(json,type);}
        catch(Exception invalid){throw new ApiException(422,"结构化交互字段、类型或大小无效");}
    }
    /**
     * 全量条件编辑，不允许借此修改报表、公司或执行权限。
     * @param queryRef 用户正在编辑的成功查询轮次
     * @param conditions 本次希望采用的完整AND/OR条件，空集合明确清除附加筛选
     */
    public record QueryEdit(String queryRef,List<BusinessQuery.Group> conditions){public QueryEdit{if(queryRef==null || !queryRef.matches("[a-f0-9]{32}") || conditions==null)throw new ApiException(422,"查询编辑引用或条件缺失");conditions=List.copyOf(conditions);}}
    /**
     * 用户对当前澄清卡片的选择。
     * @param clarificationRef 当前仍有效的澄清轮次
     * @param value 卡片提供的稳定选项值
     */
    public record ClarificationReply(String clarificationRef,String value){public ClarificationReply{if(clarificationRef==null || !clarificationRef.matches("[a-f0-9]{32}") || value==null || value.isBlank() || value.length()>128)throw new ApiException(422,"澄清回复格式无效");}}
    /**
     * 已审核但尚未完成的用户问题与候选，保存在权威会话状态。
     * @param requestId 创建澄清的服务端轮次
     * @param message 当时用户原文，持久化时采用统一脱敏
     * @param options 已通过目录和独立复核的候选
     * @param permissionVersion 创建时权限版本，变化后拒绝旧回复
     * @param dictionaryHash 创建时业务词典版本摘要
     * @param resolved 此请求此前已经完成的维度选择，后续澄清不能恢复成未选择状态
     */
    public record PendingClarification(String requestId,String message,ClarificationOptions options,String permissionVersion,String dictionaryHash,List<ResolvedChoice> resolved) {
        public PendingClarification{resolved=List.copyOf(resolved);}
        /** 首个澄清没有已经完成的选择，后续澄清必须显式传入累积选择。 */
        public PendingClarification(String requestId,String message,ClarificationOptions options,String permissionVersion,String dictionaryHash){this(requestId,message,options,permissionVersion,dictionaryHash,List.of());}
    }
    /**
     * 已完成的一项业务消歧，仅保存服务端核验过的候选范围及原值。
     * @param options 当时的范围和候选，不能跨公司或数据域解释
     * @param value 用户明确选中的稳定值
     */
    public record ResolvedChoice(ClarificationOptions options,String value){public ResolvedChoice{options.answer(value);}}
    /**
     * 已通过当前引用核验的单次用户选择，只在本轮规划中使用，不授予派单确认权限。
     * @param pending 服务端保存的原问题、候选、词典版本和已完成选择
     * @param value 用户选择的候选原值
     */
    public record SelectedClarification(PendingClarification pending,String value){
        public SelectedClarification{pending.options().answer(value);}
        public String dictionaryHash(){return pending.dictionaryHash();}
        /** 累积当前请求已完成的选择，后续模型和澄清轮次读取同一事实。 */
        public List<ResolvedChoice> choices(){var result=new ArrayList<>(pending.resolved());result.add(new ResolvedChoice(pending.options(),value));if(result.size()>8)throw new ApiException(422,"连续澄清超过预算，请重新提交完整问题");return List.copyOf(result);}
        public Map<String,Object> context(){return Map.of("originalRequest",pending.message(),"selectionCompleted",true,"resolvedChoices",choices());}
    }
    /** 在已持有的会话租约内解析编辑；过期、失败焦点或授权变化均拒绝，不退回普通文本执行。 */
    public static BusinessQuery editedQuery(CurrentUser user,DialogueState state,QueryEdit edit){
        if(edit==null)return null;
        if(state.getBusinessQuery()==null || state.isBusinessUnresolved() || !"BUSINESS_QUERY".equals(state.getAssistantFocus())
                || !Objects.equals(edit.queryRef(),state.getBusinessQueryRef()) || !Objects.equals(user.permissionVersion(),state.getBusinessPermissionVersion()))throw new ApiException(409,"该查询已变化，请重新打开最新查询后编辑");
        var q=state.getBusinessQuery();var requested=new BusinessQuery(q.domain(),q.view(),q.reportIds(),q.companyCode(),edit.conditions(),q.sortField(),q.descending(),1,q.size(),q.groupBy());
        // 先验证编辑条件类型及运算能力，未知字段不能作为提示文本进入模型后被悄悄丢弃。
        for(var group:requested.conditions())for(var condition:group.allOf()){
            var field=state.getBusinessColumns().stream().filter(f->f.name().equals(condition.field())).findFirst().orElseThrow(()->new ApiException(422,"编辑字段不属于当前查询"));
            if(!com.example.report.semantic.FieldSelection.operators(field.type()).contains(condition.operator()))throw new ApiException(422,"该字段不支持所选比较");
            condition.values().forEach(v->com.example.report.rule.FieldFact.scalar(field.type(),v));
        }
        return requested;
    }
    /** 以精确谓词和明确撤销表达完整编辑；不会从字段名或关键词猜测额外操作。 */
    public static String editMessage(DialogueState state,BusinessQuery requested){
        String conditions=requested.conditions().isEmpty()?"没有附加字段筛选":requested.conditions().stream().map(g->"（"+g.allOf().stream().map(QueryInterpretation::text).collect(java.util.stream.Collectors.joining(" 且 "))+"）").collect(java.util.stream.Collectors.joining(" 或 "));
        var current=QueryInterpretation.atoms(requested,state.getBusinessColumns());
        String removed=QueryInterpretation.atoms(state.getBusinessQuery(),state.getBusinessColumns()).entrySet().stream().filter(e->!current.containsKey(e.getKey())).map(e->"明确删除或替换旧条件："+QueryInterpretation.text(e.getValue())+"。").collect(java.util.stream.Collectors.joining());
        String result="请修改当前成功查询，保持数据域、公司、报表、展示方式、排序和每页条数不变，回到第1页。本轮完整筛选条件为："+conditions+"。"+removed;
        return bounded(result);
    }
    /** 回复必须绑定仍有效的候选及权限；从已保存问题与用户明确选择组成当前完整问题。 */
    public static String clarificationMessage(CurrentUser user,DialogueState state,ClarificationReply reply,String dictionaryHash){
        var pending=state.getPendingClarification();
        if(pending==null || !Objects.equals(reply.clarificationRef(),pending.requestId()) || !Objects.equals(user.permissionVersion(),pending.permissionVersion()) || !Objects.equals(dictionaryHash,pending.dictionaryHash()))throw new ApiException(409,"澄清选项已变化，请重新发送完整问题");
        var selected=new SelectedClarification(pending,reply.value());
        return bounded("原待完成请求（其中以下选择步骤现已完成）："+pending.message()+"\n本轮已经明确选择："
                +selected.choices().stream().map(c->c.options().answer(c.value())).collect(java.util.stream.Collectors.joining())
                +"请使用以上已完成的选择继续原请求，保留其他要求；已选择的维度不再是未决问题。选择不代表确认派单，其他真实歧义仍须澄清。");
    }
    /** 编辑后的模型草稿和独立期望必须与用户提交的完整条件等价，澄清仍可返回但不能改成其他业务动作。 */
    public static void requireRequestedQuery(AssistantPlan plan,BusinessQuery requested,List<FieldInfo> columns){
        if(requested==null || plan.route()==AssistantPlan.Route.CLARIFY)return;
        if(plan.route()!=AssistantPlan.Route.BUSINESS_QUERY || !QueryInterpretation.fingerprint(requested,columns).equals(QueryInterpretation.fingerprint(plan.query(),columns)))throw new ApiException(422,"本轮来自条件编辑器，必须完整保留用户指定的查询范围、筛选组合和展示设置，不能改变动作或省略条件");
    }
    /**
     * 草稿和独立期望共同核对结构化选择；条件的比较方向仍由原文与独立复核决定，不把选值擅自变成EQ。
     * @param plan 待校验完整计划
     * @param selected 本轮已经核实的用户选择，普通文本请求为空
     * @param facts 只读预检给出的有效范围或目标，不接受模型自报执行范围
     */
    public static void requireSelected(AssistantPlan plan,SelectedClarification selected,Map<String,Object> facts){
        if(selected==null)return;
        for(var choice:selected.choices())requireChoice(plan,choice,facts);
    }
    /** 已完成的维度不能再次索取选择；剩余维度的澄清仍允许，也不会自动转成查询或派单计划。 */
    private static void requireChoice(AssistantPlan plan,ResolvedChoice choice,Map<String,Object> facts){
        var o=choice.options();String v=choice.value();boolean matches=false;
        if(plan.route()==AssistantPlan.Route.CLARIFY){
            var next=plan.clarificationOptions();
            if(next!=null && next.kind()==o.kind() && next.domain()==o.domain() && Objects.equals(next.companyCode(),o.companyCode()) && Objects.equals(next.reportId(),o.reportId()) && Objects.equals(next.field(),o.field()))
                throw new ApiException(422,"用户已经完成这个维度的选择，不能再次要求相同选择；请根据已完成选择继续原请求，仅其他真实未决维度可以澄清");
            return;
        }
        if(plan.route()==AssistantPlan.Route.BUSINESS_QUERY){
            var q=plan.query();matches=switch(o.kind()){
                case COMPANY->v.equals(q.companyCode());
                case REPORT->q.reportIds().equals(List.of(v));
                case FIELD,VALUE->{
                    boolean scope=q.domain()==o.domain() && o.companyCode().equals(q.companyCode()) && q.reportIds().equals(List.of(o.reportId()));
                    boolean included=o.kind()==ClarificationOptions.Kind.FIELD
                            ?v.equals(q.sortField()) || v.equals(q.groupBy()) || q.conditions().stream().flatMap(g->g.allOf().stream()).anyMatch(f->v.equals(f.field()))
                            :!q.conditions().isEmpty() && q.conditions().stream().allMatch(g->g.allOf().stream().anyMatch(f->o.field().equals(f.field()) && f.values().contains(v)));
                    yield scope && included;
                }
            };
        } else if(plan.analysis()!=null) {
            // 知识与分析指令已经明确绑定单公司、单报表，必须保留所有已完成的范围选择。
            // 此处尚无可验证的分析步骤或字段谓词，不能仅凭范围一致就宣称保留了FIELD/VALUE选择。
            matches=switch(o.kind()){
                case COMPANY->v.equals(plan.analysis().companyCode());
                case REPORT->v.equals(plan.analysis().reportId());
                case FIELD,VALUE->false;
            };
        } else if(plan.route()==AssistantPlan.Route.DISPATCH && (o.kind()==ClarificationOptions.Kind.COMPANY || o.kind()==ClarificationOptions.Kind.REPORT)) {
            var evidence=JsonUtil.MAPPER.valueToTree(facts);var scope=evidence.path("scope");
            if(scope.isObject())matches=o.kind()==ClarificationOptions.Kind.COMPANY?v.equals(scope.path("companyCode").asText()):!scope.path("allReports").asBoolean() && scope.path("reportIds").size()==1 && v.equals(scope.path("reportIds").path(0).asText());
            else if(evidence.path("targetRecords").isArray() && !evidence.path("targetRecords").isEmpty()){
                matches=true;for(var target:evidence.path("targetRecords"))if(!v.equals(target.path(o.kind()==ClarificationOptions.Kind.COMPANY?"companyCode":"reportId").asText()))matches=false;
            }
        }
        if(!matches)throw new ApiException(422,"完整计划没有保留用户明确选择的公司、报表、字段或枚举；请保留选择后重新规划，其他歧义须继续澄清");
    }
    private static String bounded(String message){if(message.length()>2000)throw new ApiException(422,"编辑内容超过单轮2000字预算，请减少条件或重新描述问题");return message;}
}
