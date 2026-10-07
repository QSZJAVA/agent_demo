package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.semantic.DialogueState;
import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.TextNormalizer;
import java.util.regex.Pattern;
import java.util.*;

/** 会话焦点下的路由一致性校验，只拒绝丢失选择操作的模型草稿，不据词语生成查询或替代模型解析。 */
public final class AssistantRouteGuard {
    private AssistantRouteGuard() { }
    // 这是选择操作的通用否决条件；新业务查询须有独立查询动作，不能把负向选择静默改成正向筛选。
    private static final Pattern SELECTION=Pattern.compile("不要(?!派单|生成|执行)|排除|取消勾选|取消选择|恢复|保留|只选|选中|不动|去掉|剔除|选上|不选|别选|\\b(?:exclude|deselect|restore|keep|select)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY=Pattern.compile("查询|查一下|查看|看一下|看看|列出|统计|汇总|总结|展示|浏览|检索|搜索|\\b(?:query|show|list|search|summarize)\\b",Pattern.CASE_INSENSITIVE);
    /** 仅在派单候选焦点存在、没有独立查询动作且原文包含选择操作时，禁止改走普通业务查询。 */
    public static void validate(String message,DialogueState state,AssistantPlan plan) {
        if("BUSINESS_QUERY".equals(state.getAssistantFocus()) && plan.route()==AssistantPlan.Route.DISPATCH
                && !Pattern.compile("派单|可派|能派|待派|候选|清单|勾选|\\b(?:dispatch|candidates?|preview|plan)\\b",Pattern.CASE_INSENSITIVE).matcher(message).find())
            throw new ApiException(422,"当前焦点是只读业务查询，本轮没有明确转入候选、派单或清单流程；排除或保留记录应细化上一业务查询，不能把同一句筛选改成派单选择。");
        if("DISPATCH".equals(state.getAssistantFocus()) && state.getPreviewId()!=null && plan.route()==AssistantPlan.Route.BUSINESS_QUERY
                && SELECTION.matcher(message).find() && !QUERY.matcher(message).find())
            throw new ApiException(422,"本轮在已有派单候选焦点中调整选择，不能丢弃选择操作改为普通数据查询；请按原文重新判断完整路由");
        if(plan.route()==AssistantPlan.Route.BUSINESS_QUERY){validateInterval(message,plan.query());validateComposition(message,state,plan);validateDetailIdentity(plan.query());}
    }
    /** 独立工单查询复用旧报表范围时须有本轮依据；结束旧话题的否定不能成为工单的正向过滤。 */
    public static void validateDomainScope(String message,DialogueState state,AssistantPlan plan,List<CatalogEntry> reports) {
        if(plan.route()!=AssistantPlan.Route.BUSINESS_QUERY || plan.followUp() || state.getBusinessQuery()==null
                || plan.query().domain()!=BusinessQuery.Domain.WORK_ORDER || plan.query().domain()==state.getBusinessQuery().domain()
                || !plan.query().reportIds().equals(state.getBusinessQuery().reportIds()) || plan.query().reportIds().isEmpty())return;
        String normalized=TextNormalizer.normalize(message);
        for(String reportId:plan.query().reportIds()) {
            var entry=reports.stream().filter(r->r.reportId().equals(reportId)).findFirst();
            if(entry.isEmpty())continue; // 未知标识由正式目录权限校验拒绝。
            var names=new ArrayList<>(entry.get().ref().aliases());names.add(entry.get().reportName());names.add(entry.get().domainCode());
            var mentioned=names.stream().map(TextNormalizer::normalize).filter(n->!n.isBlank() && normalized.contains(n)).toList();
            if(mentioned.isEmpty() || mentioned.stream().allMatch(n->Pattern.compile("(?:不看|不查|停止查询|别看)"+Pattern.quote(n)).matcher(normalized).find()))
                throw new ApiException(422,"新业务域的报表范围缺少本轮肯定依据；不能继承已结束话题的报表或按工单编号猜测来源。未限定来源报表时使用全部授权报表，保留当前编号及其他明确条件。");
        }
    }
    /** 明确单张报表的“全部记录”不能扩大成全部报表；仅否决范围丢失，报表解析与动作仍由模型完成。 */
    public static void validateExplicitReport(String message,AssistantPlan plan,List<CatalogEntry> reports) {
        if(plan.route()!=AssistantPlan.Route.BUSINESS_QUERY || plan.query().domain()!=BusinessQuery.Domain.REPORT)return;
        String normalized=TextNormalizer.normalize(message);
        if(Pattern.compile("全部报表|所有报表|各报表|每张报表|其他报表|其余报表|allreports|everyreport|otherreports",Pattern.CASE_INSENSITIVE).matcher(normalized).find())return;
        var mentioned=new LinkedHashMap<String,List<String>>();
        for(var report:reports) {
            var names=new ArrayList<>(report.ref().aliases());names.add(report.reportName());
            var terms=names.stream().map(TextNormalizer::normalize).filter(n->!n.isBlank() && normalized.contains(n)).toList();
            if(!terms.isEmpty())mentioned.put(report.reportId(),terms);
        }
        if(mentioned.size()!=1)return;
        var entry=mentioned.entrySet().iterator().next();
        // 这里只识别数量词与目录名的明确组合；单独提及某字段值或历史报表不提供足够的范围证据。
        boolean allRows=entry.getValue().stream().anyMatch(n->Pattern.compile("(?:全部|所有|all|every)(?:的)?"+Pattern.quote(n)
                +"|"+Pattern.quote(n)+"(?:报表|账|账目|记录|数据|明细|单据|的)*(?:全部|所有|都)").matcher(normalized).find());
        if(!allRows)return;
        // 否定或撤销该报表限制需要完整语义判断，不能把它当作正向的单报表查询要求。
        if(entry.getValue().stream().anyMatch(n->Pattern.compile("(?:不看|不查|不要|不限于|不只看|不只查|排除|去掉)"+Pattern.quote(n)).matcher(normalized).find()))return;
        if(!plan.query().reportIds().equals(List.of(entry.getKey())))
            throw new ApiException(422,"本轮明确指定了一张报表；其中的全部记录不等于全部报表。请保留该报表标识，不能用空reportIds或其他报表扩大范围，其他明确条件仍须保留。");
    }
    /** 排序和取第一页只决定展示，不能绕过单对象详情的唯一性；仍由模型按已展示事实给出稳定标识。 */
    private static void validateDetailIdentity(BusinessQuery query) {
        if(query.view()!=BusinessQuery.View.DETAIL || query.sortField()==null)return;
        boolean identified=query.conditions().stream().allMatch(g->g.allOf().stream().anyMatch(c->
                Set.of("recordId","docNo","orderId","requestId","planId").contains(c.field()) && c.operator().equals("EQ") && c.values().size()==1));
        if(query.conditions().isEmpty() || !identified)throw new ApiException(422,"DETAIL不能仅靠排序和size=1定位单笔。请依据previousRows中的唯一目标增加稳定编号条件；并列或已展示事实不足时须澄清，不得任取第一条。");
    }
    /** 同一查询范围的追加筛选不得静默丢弃其他字段；明确取消限制或重查时仍由模型给出完整新查询。 */
    public static void validateRefinement(String message,DialogueState state,AssistantPlan plan) {
        if(plan.route()!=AssistantPlan.Route.BUSINESS_QUERY || !plan.followUp() || state.getBusinessQuery()==null)return;
        var before=state.getBusinessQuery();var after=plan.query();
        if(before.domain()!=after.domain() || !before.reportIds().equals(after.reportIds()) || !Objects.equals(before.companyCode(),after.companyCode()))return;
        var oldFields=before.conditions().stream().flatMap(g->g.allOf().stream()).map(BusinessQuery.Filter::field).collect(java.util.stream.Collectors.toSet());
        var newFields=after.conditions().stream().flatMap(g->g.allOf().stream()).map(BusinessQuery.Filter::field).collect(java.util.stream.Collectors.toSet());
        var removed=new HashSet<String>();
        for(var removal:plan.removedFilters()) {
            if(!oldFields.contains(removal.field()) || !TextNormalizer.normalize(message).contains(TextNormalizer.normalize(removal.evidence())))
                throw new ApiException(422,"removedFilters必须引用本轮连续原文，并且仅撤销上次查询已存在的字段");
            removed.add(removal.field());
        }
        oldFields.removeAll(newFields);
        oldFields.removeAll(removed);
        if(!oldFields.isEmpty())throw new ApiException(422,"本轮追问不能静默丢弃上轮字段限制："+String.join("、",new TreeSet<>(oldFields))+"。追加筛选须保留原限制；明确不再限制某字段时，在removedFilters声明该字段并逐字引用本轮撤销依据，不按固定措辞猜测。");
    }
    // 只读取显式公司代码，不把客户全称里的“公司”当作数据归属，不根据词语生成查询。
    private static final Pattern COMPANY_CODE=Pattern.compile("(?<![A-Za-z0-9_])([A-Z][A-Z0-9_-]{0,31})\\s*公司|(?i:company)\\s+([A-Z][A-Z0-9_-]{0,31})(?![A-Za-z0-9_])");
    /** 明确要求的公司不能被静默丢弃；无权范围或协议不能表达的组合需要澄清，不能只返回原范围。 */
    public static void validateCompanies(String message,AssistantPlan plan,Set<String> allowedCompanies) {
        if(plan.route()!=AssistantPlan.Route.BUSINESS_QUERY)return;
        var requested=new LinkedHashSet<String>();var matcher=COMPANY_CODE.matcher(message);
        while(matcher.find())requested.add(matcher.group(1)!=null?matcher.group(1):matcher.group(2));
        if(requested.isEmpty())return;
        if(!allowedCompanies.containsAll(requested))throw new ApiException(422,"本轮明确提到未授权公司；应说明公司权限边界，不能忽略该公司而返回原范围");
        Set<String> actual=plan.query().companyCode()==null?allowedCompanies:Set.of(plan.query().companyCode());
        if(!requested.equals(actual))throw new ApiException(422,"查询公司范围必须完整对应本轮明确请求；协议无法表达时需要澄清，不能只取部分公司");
    }
    /** 明确区间的上下界须相交；仅否决拆成 OR 的草稿，不替模型决定字段或数值。 */
    private static void validateInterval(String message,BusinessQuery query) {
        if(!(message.contains("之间") || message.contains("介于") || Pattern.compile("\\bbetween\\b",Pattern.CASE_INSENSITIVE).matcher(message).find()))return;
        for(int i=0;i<query.conditions().size();i++)for(int j=i+1;j<query.conditions().size();j++)
            for(var a:query.conditions().get(i).allOf())for(var b:query.conditions().get(j).allOf())
                if(a.field().equals(b.field()) && !bounded(query.conditions().get(i),a.field()) && !bounded(query.conditions().get(j),a.field())
                        && ((Set.of("GT","GTE").contains(a.operator()) && Set.of("LT","LTE").contains(b.operator()))
                        || (Set.of("LT","LTE").contains(a.operator()) && Set.of("GT","GTE").contains(b.operator()))))
                    throw new ApiException(422,"明确区间的上下界必须放在同一 allOf 中同时满足，不能拆成两个 OR 分支扩大查询范围；请保留全部条件重新规划");
    }
    /** 多个各自完整的区间可以取并集，不将合法的区间 OR 当作拆散上下界。 */
    private static boolean bounded(BusinessQuery.Group group,String field) {
        return group.allOf().stream().anyMatch(c->c.field().equals(field) && Set.of("GT","GTE").contains(c.operator()))
                && group.allOf().stream().anyMatch(c->c.field().equals(field) && Set.of("LT","LTE").contains(c.operator()));
    }
    /** 多个不同字段的限制默认同时成立；没有析取依据时只否决 OR 草稿，仍由模型修正完整查询。 */
    private static void validateComposition(String message,DialogueState state,AssistantPlan plan) {
        var groups=plan.query().conditions();if(groups.size()<2)return;
        if(Pattern.compile("或|要么|任一|加上|再加|\\b(?:or|either)\\b",Pattern.CASE_INSENSITIVE).matcher(message).find())return;
        // 同一字段的枚举值并集等价于IN；已建立的OR查询在排序、分页或明确追问时可继续承接。
        String field=groups.get(0).allOf().get(0).field();
        boolean enumeration=groups.stream().allMatch(g->g.allOf().size()==1 && g.allOf().get(0).field().equals(field) && g.allOf().get(0).operator().equals("EQ"));
        if(enumeration || (plan.followUp() && state.getBusinessQuery()!=null && state.getBusinessQuery().conditions().size()>1))return;
        throw new ApiException(422,"本轮多个不同字段的限制需要同时成立，应放在同一allOf；没有明确任选其一的要求时不能拆成OR组扩大结果，请重新核对完整条件");
    }
}
