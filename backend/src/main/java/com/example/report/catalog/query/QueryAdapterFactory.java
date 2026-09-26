package com.example.report.catalog.query;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.entity.ReportDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 按目录里的 query_mode / query_config 创建报表查询适配器。
 * STANDARD 直接由配置生成；ADAPTER 引用已注册的 {@link CustomReportAdapter}。
 */
@Component
public class QueryAdapterFactory {

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, CustomReportAdapter> customAdapters = new HashMap<>();

    public QueryAdapterFactory(NamedParameterJdbcTemplate jdbc, ObjectProvider<CustomReportAdapter> adapters) {
        this.jdbc = jdbc;
        adapters.orderedStream().forEach(a -> {
            if (customAdapters.putIfAbsent(a.key(), a) != null) {
                throw new IllegalStateException("报表适配器编码重复：" + a.key());
            }
        });
    }

    public ReportQueryAdapter create(String queryMode, String queryConfig) {
        if (ReportDefinition.MODE_STANDARD.equals(queryMode)) {
            return new StandardReportAdapter(StandardQueryConfig.parse(queryConfig), jdbc);
        }
        if (ReportDefinition.MODE_ADAPTER.equals(queryMode)) {
            Object key = JsonUtil.toMap(queryConfig).get("adapter");
            CustomReportAdapter adapter = key == null ? null : customAdapters.get(String.valueOf(key));
            if (adapter == null) {
                throw new ApiException("没有找到报表适配器：" + key);
            }
            return adapter;
        }
        throw new ApiException("query_mode 只能是 STANDARD 或 ADAPTER");
    }

    /** 发布前校验：配置能解析、适配器存在；标准报表还要确认表和列在库里真实存在 */
    public ReportQueryAdapter createAndProbe(String queryMode, String queryConfig) {
        ReportQueryAdapter adapter = create(queryMode, queryConfig);
        if (adapter instanceof StandardReportAdapter standard) {
            try {
                standard.probe();
            } catch (DataAccessException e) {
                String reason = e.getMostSpecificCause().getMessage();
                throw new ApiException("查询配置与数据库不一致：" + reason);
            }
        }
        return adapter;
    }
}
