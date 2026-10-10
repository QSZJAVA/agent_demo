package com.example.report.assistant;

import com.example.report.common.ApiException;

/**
 * 详细复核前的独立目标判断；固定本轮结果类型、查询连续性和最终数量，不携带记录、范围或执行授权。
 * @param requestedResult 对用户本轮所需业务结果的一句说明，先于枚举判断；不能用系统前置步骤代替用户目标
 * @param purpose 期望结果对应的动作类别；查询筛选、候选预览及清单准备分别判断
 * @param queryContext 普通查询区分独立新任务、承接成功查询和重建尚未完成请求；非查询使用NOT_QUERY，不把失败查询当作成功对象
 * @param evidence 支撑目标的本轮连续原文，历史只能用于解释指代
 * @param targetCount 本阶段识别到的本轮最终候选或清单总条数；null表示未识别到，详细复核仍须核对是否有明确总数，局部操作量不在此列
 * @param queryDomain 普通查询的业务数据域；非普通查询为null。报表、派单记录和工单不能因都是只读而相互承接
 */
public record TaskPurpose(String requestedResult,Purpose purpose,QueryContext queryContext,String evidence,SemanticReview.TargetCount targetCount,BusinessQuery.Domain queryDomain) {
    public TaskPurpose {
        if(requestedResult==null || requestedResult.isBlank() || requestedResult.length()>512 || purpose==null || queryContext==null || (purpose==Purpose.BUSINESS_QUERY)==(queryContext==QueryContext.NOT_QUERY)
                || evidence==null || evidence.isBlank() || evidence.length()>1000)
            throw new ApiException(422,"独立任务目标缺少动作类别或本轮依据");
        if(targetCount!=null && purpose!=Purpose.PREVIEW && purpose!=Purpose.PREPARE_DISPATCH && purpose!=Purpose.CLARIFY)
            throw new ApiException(422,"最终目标条数只用于候选选择或待确认清单，普通查询、金额和局部操作数量不填targetCount");
        if((purpose==Purpose.BUSINESS_QUERY)!=(queryDomain!=null))throw new ApiException(422,"BUSINESS_QUERY须明确queryDomain为REPORT、DISPATCH或WORK_ORDER，其他目标的queryDomain必须为null");
    }
    /** 内部构造已声明的目标，描述仅用于程序实例；模型JSON必须显式提供requestedResult，不接受旧结构缺字段。 */
    public TaskPurpose(Purpose purpose,QueryContext queryContext,String evidence,SemanticReview.TargetCount count,BusinessQuery.Domain domain){this(java.util.Objects.toString(purpose,""),purpose,queryContext,evidence,count,domain);}
    /** 程序内部构造报表查询或非查询目标；模型JSON仍须显式提供全部字段，不兼容缺字段输出。 */
    public TaskPurpose(Purpose purpose,QueryContext queryContext,String evidence,SemanticReview.TargetCount count){this(purpose,queryContext,evidence,count,purpose==Purpose.BUSINESS_QUERY?BusinessQuery.Domain.REPORT:null);}
    public TaskPurpose(Purpose purpose,QueryContext queryContext,String evidence){this(purpose,queryContext,evidence,null);}
    /** 业务结果类别；不包含确认执行、源业务修改或外部审批能力。 */
    public enum Purpose { BUSINESS_QUERY, PREVIEW, PREPARE_DISPATCH, CANCEL_PLAN, SHOW_RESULT, EXPLAIN_RULES, HELP, CLARIFY }
    /** INDEPENDENT为独立新任务；FOLLOW_UP承接当前成功查询；RECOVERY按本轮补充重建未完成请求，不继承成功状态；NOT_QUERY为非查询。 */
    public enum QueryContext { INDEPENDENT, FOLLOW_UP, RECOVERY, NOT_QUERY }
    /** 只读取已经明确的协议动作，不从原文推断路由。 */
    public static Purpose action(AssistantPlan plan) {
        return plan.dispatch()==null?Purpose.valueOf(plan.route().name()):Purpose.valueOf(plan.dispatch().intent().action().name());
    }
    /** 当前能力或对象前提仍可能要求澄清；目标判断不能绕过后续完整预检。 */
    public boolean permits(AssistantPlan plan){return plan.route()==AssistantPlan.Route.CLARIFY || (action(plan)==purpose
            && (plan.route()!=AssistantPlan.Route.BUSINESS_QUERY || (plan.query().domain()==queryDomain && plan.followUp()==(queryContext==QueryContext.FOLLOW_UP))));}

    /**
     * 目标冻结前核对查询承接的程序前提；只拒绝不一致，不从词语推断动作或替模型选择新目标。
     * @param activeQuery 当前焦点中的成功查询；焦点已切到候选或没有成功查询时为null
     * @param unresolved 当前查询请求是否尚未解决；失败请求不能成为可承接的成功对象
     * @throws ApiException FOLLOW_UP没有当前成功查询或跨数据域，或RECOVERY不存在未完成查询；仅在目标阶段自身修正
     */
    public void validateContext(BusinessQuery activeQuery,boolean unresolved) {
        if(queryContext==QueryContext.RECOVERY) {
            if(!unresolved)throw new com.example.report.common.ModelContractViolation("当前没有尚未完成的查询需要重建",
                    "RECOVERY仅用于queryUnresolved=true且本轮明确补充或继续尚未完成的查询。已有成功查询的细化使用FOLLOW_UP，独立新对象使用INDEPENDENT；不能把成功状态改称失败来取得重建权限");
            return;
        }
        if(queryContext!=QueryContext.FOLLOW_UP)return;
        if(activeQuery==null || unresolved)throw new com.example.report.common.ModelContractViolation("当前没有可承接的成功查询",
                "FOLLOW_UP必须存在activeQuery且queryUnresolved=false。当前候选与普通查询是不同对象；按当前焦点重新判断要修改的对象和结果类型，不能从隐藏的旧查询继承筛选，也不能为通过校验把候选选择改成独立查询。");
        if(activeQuery.domain()!=queryDomain)throw new com.example.report.common.ModelContractViolation("切换业务对象不能继承上一数据域",
                "activeQuery.domain="+activeQuery.domain()+"，本轮queryDomain="+queryDomain+"；跨数据域必须INDEPENDENT，按本轮完整范围建立新查询，不因“再看”或“继续”等词保留FOLLOW_UP。不要更换本轮数据域来通过校验。");
    }

    /**
     * 核对详细期望是否保留独立目标；失败仅反馈复核，不改写任何操作或补足记录。
     * @param review 待冻结的完整期望；前提不足仍可澄清，但必须保留已识别的最终总数
     * @throws ApiException 动作、查询承接或总数不一致，详细复核须在原预算内重新完整判断
     */
    public void validate(SemanticReview review) {
        var failures=new java.util.ArrayList<String>();
        if(!permits(review.expectedPlan()))failures.add("独立业务目标为 "+purpose+"，queryContext="+queryContext
                +"，queryDomain="+queryDomain+"；expectedPlan须符合动作、业务数据域及查询连续性。INDEPENDENT为新任务且followUp=false；RECOVERY按本轮补充与已明确的未完成请求重建完整范围，followUp=false且不冒充成功查询；FOLLOW_UP才承接成功查询。前提不足可CLARIFY，不能替换用户目标");
        Integer expected=targetCount==null?null:targetCount.count();
        Integer actual=review.targetCount()==null?null:review.targetCount().count();
        // 已识别的数量不可削弱；未识别到数量不是禁止进一步识别，完整复核新声明的数量仍须引用本轮并接受实数校验。
        if(expected!=null && !java.util.Objects.equals(expected,actual))failures.add("taskPurpose.targetCount已独立确定最终总数为 "+expected
                +"；复核targetCount必须保留同一总数，不能漏填或改数。实际选择不足时应CLARIFY并保留数量要求，不能增加或减少目标");
        if(!failures.isEmpty())throw new com.example.report.common.ModelContractViolation("本轮任务目标或最终数量尚未一致",String.join("；",failures));
    }
}
