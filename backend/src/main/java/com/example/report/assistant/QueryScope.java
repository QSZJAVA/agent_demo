package com.example.report.assistant;

import java.util.Set;

/** 当前授权快照下的查询范围比较；只展开协议中的全部范围，不从旧查询推测权限，不修改查询或过滤条件。 */
final class QueryScope {
    private QueryScope() { }

    /**
     * 比较两份查询实际覆盖的数据域、报表和公司；须在权限预检边界内调用。
     * @param before 请求前的成功查询，null表示没有可比较对象
     * @param after 当前完整查询，null表示非查询
     * @param reports 当前授权报表全集，空查询报表数组在此快照内展开
     * @param companies 当前授权公司全集，null公司范围在此快照内展开
     * @return 两份查询覆盖完全相同的授权范围时为true；不以有数据的报表或当前返回集合代替范围
     */
    static boolean same(BusinessQuery before,BusinessQuery after,Set<String> reports,Set<String> companies) {
        return before!=null && after!=null && before.domain()==after.domain()
                && reportScope(before,reports).equals(reportScope(after,reports))
                && companyScope(before,companies).equals(companyScope(after,companies));
    }
    private static Set<String> reportScope(BusinessQuery query,Set<String> authorized) {return query.reportIds().isEmpty()?authorized:Set.copyOf(query.reportIds());}
    private static Set<String> companyScope(BusinessQuery query,Set<String> authorized) {return query.companyCode()==null?authorized:Set.of(query.companyCode());}
}
