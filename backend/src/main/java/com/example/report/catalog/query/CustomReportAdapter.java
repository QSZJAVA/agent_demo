package com.example.report.catalog.query;

import java.util.Collection;
import java.util.List;

/**
 * 复杂报表的专用适配器：以 Spring Bean 注册，目录里配置 query_mode=ADAPTER、query_config={"adapter":"key()"}。
 * 新增复杂报表需要写一个实现类，但仍然不需要修改解析、规则、预览、派单等通用代码。
 */
public interface CustomReportAdapter extends ReportQueryAdapter {

    /** 复杂报表也必须提供有界的待派单主键复核。 */
    @Override
    List<FactRow> pendingRowsByIds(String tenantId, Collection<String> recordIds);

    /** 适配器编码，对应 query_config.adapter */
    String key();
}
