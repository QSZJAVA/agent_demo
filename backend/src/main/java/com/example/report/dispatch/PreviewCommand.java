package com.example.report.dispatch;

import java.util.List;

/**
 * 统一预览请求协议（P0-05）。语义服务、报表选择卡片和REST接口都转换成它：
 * 报表只能以“用户说法”（由服务端解析）或“目录中的 report_id”（服务端按权限校验）两种形式出现，
 * 筛选条件只有白名单字段，不存在表名、字段名或 SQL 的入口。
 *
 * @param operation   操作，目前只有 PREVIEW
 * @param source      semantic / selection / api
 * @param reportQuery 用户对报表的说法，空表示全部可派单报表
 * @param reportIds   显式指定的报表（选择卡片、REST），优先于 reportQuery
 * @param filters     白名单筛选条件
 * @param scopeMode   replace / append / remove：替换、追加到上一轮范围、从上一轮范围中去掉
 */
public record PreviewCommand(
        String operation,
        String source,
        String reportQuery,
        List<String> reportIds,
        Filters filters,
        String scopeMode
) {

    public static final String OPERATION_PREVIEW = "PREVIEW";

    public PreviewCommand {
        operation = operation == null ? OPERATION_PREVIEW : operation;
        reportIds = reportIds == null ? List.of() : List.copyOf(reportIds);
        filters = filters == null ? new Filters(null) : filters;
    }

    /**
     * @param companyCode 用户明确说出的公司（组织），必须在当前用户可见范围内；空表示用户的全部可见公司
     */
    public record Filters(String companyCode) {
    }
}
