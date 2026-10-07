package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.semantic.DialogueState;
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
        if("DISPATCH".equals(state.getAssistantFocus()) && state.getPreviewId()!=null && plan.route()==AssistantPlan.Route.BUSINESS_QUERY
                && SELECTION.matcher(message).find() && !QUERY.matcher(message).find())
            throw new ApiException(422,"本轮在已有派单候选焦点中调整选择，不能丢弃选择操作改为普通数据查询；请按原文重新判断完整路由");
        if(plan.route()==AssistantPlan.Route.BUSINESS_QUERY){validateInterval(message,plan.query());validateComposition(message,state,plan);}
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
