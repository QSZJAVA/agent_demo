package com.example.report.catalog.query;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 报表查询适配器：把一张报表的数据统一成候选记录。标准报表由 {@link StandardReportAdapter} 按配置实现，
 * 复杂报表实现 {@link CustomReportAdapter} 并在目录里以 ADAPTER 模式引用。
 * 数据范围（租户、公司）由调用方从登录态传入，适配器必须把它作为查询条件，不能返回范围外的记录。
 */
public interface ReportQueryAdapter {

    /** 规则里可用的字段 */
    List<FieldInfo> fields();

    /** 单据号的展示名，例如“订单号” */
    String docNoLabel();

    /** 粗筛：只取指定租户、指定公司范围内未派单的记录 */
    List<FactRow> pendingRows(String tenantId, Set<String> companies);

    /** 按主键取记录当前状态（报表页手工派单用），同样受租户约束 */
    List<FactRow> rowsByIds(String tenantId, Collection<String> recordIds);
}
