package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.semantic.DialogueState;
import java.util.*;

/**
 * 统一任务的结构与引用校验；只校验成功上下文、稳定身份和显式撤销声明。
 * 动作、否定、数量、条件组合与话题继承由通用语义复核判断，不维护自然语言词表。
 */
public final class AssistantRouteGuard {
    private AssistantRouteGuard() { }

    /** 业务查询必须绑定可靠上下文；排序不能代替单对象身份，核验不能任取未展示记录。 */
    public static void validate(String message,DialogueState state,AssistantPlan plan) {
        if(plan.route()!=AssistantPlan.Route.BUSINESS_QUERY)return;
        if(plan.followUp()) {
            if(state.getBusinessQuery()==null || state.isBusinessUnresolved())
                throw new ApiException(422,"上次查询未完成，请重新明确查询对象和筛选条件");
            if(plan.query().domain()!=state.getBusinessQuery().domain())
                throw new ApiException(422,"连续查询必须保留上一查询的数据域；切换业务对象须声明独立查询");
        }
        validateDetailIdentity(plan.query());
        if(plan.query().view()==BusinessQuery.View.ELIGIBILITY)validateEligibilityTarget(message,state,plan);
        validateRefinement(message,state,plan);
    }

    /** 核验读取指定对象的当前事实；绑定查询身份后不用旧金额或状态条件遮蔽变化。 */
    private static void validateEligibilityTarget(String message,DialogueState state,AssistantPlan plan) {
        if(!plan.removedFilters().isEmpty())throw new ApiException(422,"资格核验按稳定身份读取当前对象，removedFilters须为空");
        var identities=plan.query().conditions().get(0).allOf();
        if(!plan.followUp()) {
            if(identities.stream().anyMatch(f->!message.contains(f.values().get(0))))
                throw new ApiException(422,"独立核验的编号须由本轮提供；引用已展示对象应声明followUp，不能猜测编号");
            return;
        }
        var targets=state.getBusinessReferences().stream().filter(row->plan.query().reportIds().contains(row.get("reportId")))
                .filter(row->plan.query().companyCode()==null || plan.query().companyCode().equals(row.get("companyCode")))
                .filter(row->identities.stream().allMatch(f->f.values().get(0).equals(row.get(f.field())))).toList();
        if(targets.size()!=1)throw new ApiException(422,"资格核验须唯一绑定已展示的报表记录，不能猜测未展示对象");
    }

    private static void validateDetailIdentity(BusinessQuery query) {
        if(query.view()!=BusinessQuery.View.DETAIL || query.sortField()==null)return;
        boolean identified=query.conditions().stream().allMatch(g->g.allOf().stream().anyMatch(c->
                Set.of("recordId","docNo","orderId","requestId","planId").contains(c.field()) && c.operator().equals("EQ") && c.values().size()==1));
        if(query.conditions().isEmpty() || !identified)
            throw new ApiException(422,"DETAIL不能仅靠排序和size=1定位单笔，须明确稳定编号或先查询列表；并列对象不能任取第一条");
    }

    /** 同一查询的字段撤销必须显式声明并引用原文；声明是否符合原意仍由语义复核验证。 */
    public static void validateRefinement(String message,DialogueState state,AssistantPlan plan) {
        if(plan.route()!=AssistantPlan.Route.BUSINESS_QUERY || !plan.followUp() || state.getBusinessQuery()==null
                || plan.query().view()==BusinessQuery.View.ELIGIBILITY)return;
        var before=state.getBusinessQuery();var after=plan.query();
        var oldFields=before.conditions().stream().flatMap(g->g.allOf().stream()).map(BusinessQuery.Filter::field).collect(java.util.stream.Collectors.toSet());
        var removed=new HashSet<String>();
        for(var removal:plan.removedFilters()) {
            if(!oldFields.contains(removal.field()) || !message.contains(removal.evidence()) || !removed.add(removal.field()))
                throw new ApiException(422,"removedFilters须唯一声明上次存在的字段，并引用本轮连续原文");
        }
        if(before.domain()!=after.domain() || !before.reportIds().equals(after.reportIds()) || !Objects.equals(before.companyCode(),after.companyCode()))return;
        var newFields=after.conditions().stream().flatMap(g->g.allOf().stream()).map(BusinessQuery.Filter::field).collect(java.util.stream.Collectors.toSet());
        oldFields.removeAll(newFields);oldFields.removeAll(removed);
        if(!oldFields.isEmpty())throw new ApiException(422,"追问遗漏上轮字段限制："+String.join("、",new TreeSet<>(oldFields))+"；保留限制，或在removedFilters声明本轮明确撤销的依据");
    }
}
