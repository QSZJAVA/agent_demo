package com.example.report.assistant;

import com.example.report.common.*;
import com.example.report.dispatch.RecordKey;
import com.example.report.dispatch.RecordTarget;
import com.example.report.semantic.DialogueState;
import com.example.report.semantic.SemanticIntent;
import java.util.*;

/** 查询、候选与清单共用的目标引用边界；引用只证明对象身份，执行前仍须重新验证权限、来源状态和规则。 */
public final class AssistantReferences {
    private AssistantReferences() { }

    /** 本轮模型可用的查询对象集；仅包含上次成功展示的事实，完整性由服务端计数确定。 */
    public static Map<String,Object> queryContext(DialogueState state) {
        String unavailableReason=state.getBusinessQuery()==null?"NO_QUERY":state.isBusinessUnresolved()?"UNRESOLVED_QUERY":
                state.getBusinessQuery().domain()!=BusinessQuery.Domain.REPORT?"NOT_REPORT_QUERY":state.getBusinessQuery().view()==BusinessQuery.View.SUMMARY?"SUMMARY_ONLY":null;
        boolean available=unavailableReason==null;
        var rows=new ArrayList<Map<String,String>>();
        if(available)for(int i=0;i<state.getBusinessReferences().size();i++) {
            var row=new LinkedHashMap<>(state.getBusinessReferences().get(i));row.put("referenceKey","row-"+(i+1));rows.add(row);
        }
        var result=new LinkedHashMap<String,Object>();result.put("available",available);result.put("sourceRef",available?queryRef(state):null);
        // 向模型同时提供业务原因和恢复方式，避免把内部字段缺失误说成要求用户提供引用键。
        result.put("unavailableReason",unavailableReason);
        result.put("recoveryHint",available?null:switch(unavailableReason) {
            case "SUMMARY_ONLY" -> "当前只有只读汇总，尚无已核对的具体派单对象；请先列出业务记录或重新核对可派候选范围，再准备清单";
            case "UNRESOLVED_QUERY" -> "上次查询未成功，请先明确对象并重新查询，成功结果才能用于准备清单";
            case "NOT_REPORT_QUERY" -> "当前查询的是派单记录或工单，不能把它们当作报表派单对象；请先查询所需业务报表记录";
            default -> "尚无成功展示的业务记录，请先明确并查询要处理的报表对象";
        });
        result.put("allMatchesDisplayed",available && complete(state));result.put("totalCount",state.getBusinessTotalCount());result.put("rows",rows);return result;
    }
    /** 引用绑定查询、展示对象及权限版本；同一轮租约内解析和执行都重新计算，不能引用被替换的结果集。 */
    public static String queryRef(DialogueState state) {
        return "query-"+Digests.sha256(JsonUtil.toJson(Arrays.asList(state.getBusinessQuery(),state.getBusinessReferences(),state.getBusinessTotalCount(),state.getBusinessPermissionVersion()))).substring(0,16);
    }
    public static String previewRef(DialogueState state) {return state.getPreviewId()==null?null:"preview-"+state.getPreviewId();}
    public static String planRef(DialogueState state) {return state.getPlanId()==null?null:"plan-"+state.getPlanId();}
    /** 是否确实展示了完整成功结果；零条不产生可派单目标，未知计数不能推断为完整。 */
    public static boolean complete(DialogueState state) {
        return state.getBusinessQuery()!=null && !state.isBusinessUnresolved() && state.getBusinessQuery().page()==1
                && state.getBusinessTotalCount()!=null && state.getBusinessTotalCount()==(long)state.getBusinessReferences().size();
    }
    /** 校验本轮动作依据及来源绑定，不识别同义词或通过语言模式替模型选择动作。 */
    public static void validate(String message,DialogueState state,DispatchDirective directive) {
        CurrentTextEvidence.require(message,directive.evidence(),"dispatch.evidence");
        switch(directive.source()) {
            case QUERY_ROWS,QUERY_ALL -> resolveQuery(state,directive);
            case PREVIEW -> {
                if(state.getPreviewId()==null || !Objects.equals(previewRef(state),directive.sourceRef()))throw new ApiException(422,"候选引用不存在或已变化，请依据当前上下文重新规划");
                // 跨话题仍保留候选供恢复，但不能把隐藏的旧集合当作当前已核对的目标直接建单。
                if(state.isBusinessQueryAfterPreview() && directive.intent().action()==SemanticIntent.Action.PREPARE_DISPATCH)
                    throw new ModelContractViolation("当前话题已切换到只读业务查询，原派单候选尚未重新展示核对；请先返回并核对候选范围，再生成待确认清单",
                            "PREVIEW来源的旧候选尚未重新展示核对，不能PREPARE_DISPATCH。若本轮只请求准备，expectedPlan须CLARIFY并说明候选前提，不能擅自改成PREVIEW来执行另一动作；仅本轮明确要求重新展示候选时才可PREVIEW。用户目标仍保留在requirements，澄清时conditionChecks=[]。");
                // 失败可能发生在选择解析之前，只留下全局未解决标记；稳定预览引用不证明上一轮操作已经生效。
                if(state.isUnresolvedRequest() && directive.intent().action()==SemanticIntent.Action.PREPARE_DISPATCH
                        && directive.intent().scopeChanges().isEmpty())
                    throw new ModelContractViolation("上一轮要求尚未完成，不能把失败前的选择当作调整后的结果；请先重新核对当前候选或明确重新选择，再生成清单",
                            "dispatchUnresolved=true且本轮PREPARE_DISPATCH没有明确的新范围或记录操作，禁止直接承接旧选择。expectedPlan应CLARIFY并解释上次修改未生效；不得假定失败操作成功或补做历史操作。用户明确要求重新展示候选可PREVIEW，或按本轮明确的完整选择重新预检；不能把仅请求准备擅自改成另一动作");
            }
            case PLAN -> {
                if(state.getPlanId()==null || !Objects.equals(planRef(state),directive.sourceRef()))throw new ApiException(422,"清单引用不存在或已变化，请依据当前上下文重新规划");
            }
            case EXPLICIT_SCOPE -> {
                boolean establishes=directive.intent().scopeChanges().stream().anyMatch(c->c.target()!=SemanticIntent.Target.RECORDS
                        && (c.operation()==SemanticIntent.Operation.REPLACE || c.operation()==SemanticIntent.Operation.ALL_AUTHORIZED));
                if(!establishes)throw new ApiException(422,"新派单范围须在完整计划中建立公司或报表范围；全部报表也需要明确ALL_AUTHORIZED，不能静默沿用初始或旧范围");
            }
        }
    }
    /**
     * 将模型选择的展示键解析为服务端持有的稳定业务标识，保持对象集合完整；失败不返回部分目标。
     * @param state 当前租约中的权威会话状态
     * @param directive 已声明查询对象来源的完整任务
     * @return 精确目标及其原公司边界；当前资格由后续业务读取复核
     */
    public static List<RecordTarget> resolveQuery(DialogueState state,DispatchDirective directive) {
        // 汇总表达的是统计范围，不是用户已核对的可派对象清单；即使底层带当前页，也不能转为批量目标。
        if(state.getBusinessQuery()!=null && state.getBusinessQuery().view()==BusinessQuery.View.SUMMARY)
            throw new ApiException(422,"上次查询是只读汇总，不能直接作为派单目标；请先列出具体业务记录或核对可派候选范围");
        if(state.getBusinessQuery()==null || state.isBusinessUnresolved() || state.getBusinessQuery().domain()!=BusinessQuery.Domain.REPORT
                || !queryRef(state).equals(directive.sourceRef()))throw new ApiException(422,"没有可引用的成功报表查询，或查询引用已变化，请明确当前目标");
        var rows=state.getBusinessReferences();
        if(directive.source()==DispatchDirective.Source.QUERY_ALL && !complete(state))
            throw new ApiException(422,"当前仅展示查询的一部分，不能把当前页当作全部匹配目标；请先取得完整且有界的目标集合");
        var keys=directive.source()==DispatchDirective.Source.QUERY_ALL
                ?java.util.stream.IntStream.range(0,rows.size()).mapToObj(i->"row-"+(i+1)).toList():directive.referenceKeys();
        if(keys.isEmpty())throw new ApiException(422,"当前查询没有可用于派单的目标记录");
        var result=new ArrayList<RecordTarget>();var unique=new HashSet<RecordKey>();
        for(String key:keys) {
            int index=Integer.parseInt(key.substring(4))-1;
            if(index<0 || index>=rows.size())throw new ApiException(422,"目标引用超出已展示记录，不能猜测未展示对象");
            var row=rows.get(index);String report=row.get("reportId"),record=row.get("recordId"),company=row.get("companyCode");
            if(report==null || record==null || company==null || !state.getBusinessQuery().reportIds().contains(report)
                    || (state.getBusinessQuery().companyCode()!=null && !state.getBusinessQuery().companyCode().equals(company)))
                throw new ApiException(422,"查询对象缺少稳定身份或超出原查询范围，请重新取得业务事实");
            var stable=new RecordKey(report,record);if(!unique.add(stable))throw new ApiException(422,"查询对象身份重复，不能生成不完整清单");
            result.add(new RecordTarget(stable,company));
        }
        return List.copyOf(result);
    }
}
