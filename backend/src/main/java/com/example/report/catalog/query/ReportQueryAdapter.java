package com.example.report.catalog.query;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 报表查询适配器：将来源数据统一为事实，分别提供全部业务数据读取与派单候选查询。标准报表由 {@link StandardReportAdapter} 按配置实现，
 * 复杂报表实现 {@link CustomReportAdapter} 并在目录里以 ADAPTER 模式引用。
 * 数据范围（租户、公司）由调用方从登录态传入，适配器必须把它作为查询条件，不能返回范围外的记录。
 */
public interface ReportQueryAdapter {
    default ReportQueryAdapter forUser(com.example.report.permission.CurrentUser user) { return this; }

    /** 规则里可用的字段 */
    List<FieldInfo> fields();

    /**
     * 按稳定主键有界读取全部业务记录，包含已派单数据；租户和公司必须在数据源内过滤。
     * 未实现此能力的自定义适配器明确拒绝，不能拿待派单子集冒充全部报表。
     */
    default List<ReportDataRow> dataRowsAfter(String tenantId,Set<String> companies,String afterId,int size) {
        throw new com.example.report.common.ApiException(422,"该报表尚未提供全部业务数据查询接口");
    }

    /** 单据号的展示名，例如“订单号”*/
    String docNoLabel();

    /** 粗筛：只取指定租户、指定公司范围内未派单的记录 */
    List<FactRow> pendingRows(String tenantId, Set<String> companies);

    /**
     * Bounded scan of all pending facts (no rule pushdown, so trial totals stay exact).
     * Custom adapters must implement this at the data source; never load all rows as a fallback.
     */
    default List<FactRow> dryRunRowsAfter(String tenantId, Set<String> companies, String afterId, int size) {
        throw new com.example.report.common.ApiException("该报表尚未配置有界试算扫描，请联系管理员配置适配器");
    }

    /** 有界分页扫描；自定义适配器在处理大表时应覆盖此方法。 */
    default List<FactRow> pendingRowsPage(String tenantId, Set<String> companies, int offset, int size) {
        List<FactRow> rows = pendingRows(tenantId, companies);
        return offset >= rows.size() ? List.of() : rows.subList(offset, Math.min(rows.size(), offset + size));
    }

    /** 简单规则可由标准适配器下推到 SQL；复杂规则仍由调用方逐行复核。*/
    default List<FactRow> pendingRowsPageWithRule(String tenantId, Set<String> companies,
                                                   int offset, int size, String expression) {
        return pendingRowsPage(tenantId, companies, offset, size);
    }

    /** 扫描专用的稳定主键游标；自定义适配器应在数据源内实现此条件。 */
    default List<FactRow> pendingRowsAfterWithRule(String tenantId, Set<String> companies,
                                                    String afterId, int size, String expression) {
        return pendingRows(tenantId, companies).stream()
                .filter(row -> afterId == null || row.recordId().compareTo(afterId) > 0)
                .sorted(java.util.Comparator.comparing(FactRow::recordId))
                .limit(size).toList();
    }

    /** 按主键取记录当前状态（报表页手工派单用），同样受租户约束*/
    List<FactRow> rowsByIds(String tenantId, Collection<String> recordIds);

    /** Revalidation must only return records still pending in the source system. */
    default List<FactRow> pendingRowsByIds(String tenantId, Collection<String> recordIds) {
        throw new UnsupportedOperationException("报表适配器尚未实现按主键复核待派单状态");
    }
}
