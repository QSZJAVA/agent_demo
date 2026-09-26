package com.example.report.catalog.query;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标准报表适配器：按 {@link StandardQueryConfig} 生成查询，替代过去每张报表一个手写的事实装配器。
 * 公司范围、租户、待派单状态都是强制条件；标识符来自校验过的配置并加反引号，值全部参数绑定。
 */
public class StandardReportAdapter implements ReportQueryAdapter, DispatchStatusWriter {

    private final StandardQueryConfig config;
    private final NamedParameterJdbcTemplate jdbc;
    private final List<FieldInfo> fields;
    private final String selectFrom;
    private final String orderBy;

    public StandardReportAdapter(StandardQueryConfig config, NamedParameterJdbcTemplate jdbc) {
        config.validate();
        this.config = config;
        this.jdbc = jdbc;
        List<FieldInfo> list = new ArrayList<>();
        config.fields().forEach(f -> list.add(new FieldInfo(f.name(), f.type(), f.description())));
        config.derived().forEach(d -> list.add(new FieldInfo(d.name(), StandardQueryConfig.derivedType(d), d.description())));
        this.fields = List.copyOf(list);

        Set<String> columns = new LinkedHashSet<>();
        columns.add(config.idColumn());
        columns.add(config.companyColumn());
        columns.add(config.docNoColumn());
        columns.add(config.statusColumn());
        columns.add(config.tenantColumn());
        addIfPresent(columns, config.labelColumn());
        addIfPresent(columns, config.amountColumn());
        addIfPresent(columns, config.dateColumn());
        config.fields().forEach(f -> columns.add(f.column()));
        config.derived().forEach(d -> addIfPresent(columns, d.column()));
        this.selectFrom = "SELECT " + String.join(", ", columns.stream().map(StandardReportAdapter::quote).toList())
                + " FROM " + quote(config.table());
        StringBuilder order = new StringBuilder(" ORDER BY ").append(quote(config.companyColumn()));
        if (StandardQueryConfig.present(config.dateColumn())) {
            order.append(", ").append(quote(config.dateColumn()));
        }
        this.orderBy = order.append(", ").append(quote(config.idColumn())).toString();
    }

    @Override
    public List<FieldInfo> fields() {
        return fields;
    }

    @Override
    public String docNoLabel() {
        return config.displayDocNoLabel();
    }

    @Override
    public List<FactRow> pendingRows(String tenantId, Set<String> companies) {
        if (companies == null || companies.isEmpty() || !tenantUsable(tenantId)) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("companies", companies)
                .addValue("pending", config.pendingValue());
        StringBuilder sql = new StringBuilder(selectFrom)
                .append(" WHERE ").append(quote(config.companyColumn())).append(" IN (:companies)")
                .append(" AND ").append(quote(config.statusColumn())).append(" = :pending");
        appendTenant(sql, params, tenantId);
        sql.append(orderBy);
        LocalDate today = LocalDate.now();
        return jdbc.query(sql.toString(), params, (rs, i) -> mapRow(rs, today));
    }

    @Override
    public List<FactRow> rowsByIds(String tenantId, Collection<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty() || !tenantUsable(tenantId)) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ids", recordIds);
        StringBuilder sql = new StringBuilder(selectFrom)
                .append(" WHERE ").append(quote(config.idColumn())).append(" IN (:ids)");
        appendTenant(sql, params, tenantId);
        sql.append(orderBy);
        LocalDate today = LocalDate.now();
        return jdbc.query(sql.toString(), params, (rs, i) -> mapRow(rs, today));
    }

    @Override
    public boolean markDispatched(String tenantId, String recordId, LocalDateTime dispatchedAt) {
        if (recordId == null || !tenantUsable(tenantId)) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", recordId)
                .addValue("pending", config.pendingValue())
                .addValue("dispatched", config.dispatchedValue());
        StringBuilder sql = new StringBuilder("UPDATE ").append(quote(config.table()))
                .append(" SET ").append(quote(config.statusColumn())).append(" = :dispatched");
        if (StandardQueryConfig.present(config.dispatchedAtColumn())) {
            sql.append(", ").append(quote(config.dispatchedAtColumn())).append(" = :dispatchedAt");
            params.addValue("dispatchedAt", dispatchedAt);
        }
        sql.append(" WHERE ").append(quote(config.idColumn())).append(" = :id")
                .append(" AND ").append(quote(config.statusColumn())).append(" = :pending");
        appendTenant(sql, params, tenantId);
        return jdbc.update(sql.toString(), params) == 1;
    }

    /** 发布前探测：按配置跑一条不返回数据的查询，表名、列名写错会在这里以 SQL 异常暴露 */
    public void probe() {
        jdbc.query(selectFrom + " WHERE 1 = 0", new MapSqlParameterSource(), (rs, i) -> null);
    }

    /** 配置了租户列而登录态没有租户时不返回任何数据：宁可查不到，也不能跨租户 */
    private boolean tenantUsable(String tenantId) {
        return tenantId != null && !tenantId.isBlank();
    }

    private void appendTenant(StringBuilder sql, MapSqlParameterSource params, String tenantId) {
        if (StandardQueryConfig.present(config.tenantColumn())) {
            sql.append(" AND ").append(quote(config.tenantColumn())).append(" = :tenantId");
            params.addValue("tenantId", tenantId);
        }
    }

    private FactRow mapRow(ResultSet rs, LocalDate today) throws SQLException {
        String label = StandardQueryConfig.present(config.labelColumn()) ? rs.getString(config.labelColumn()) : null;
        BigDecimal amount = StandardQueryConfig.present(config.amountColumn()) ? rs.getBigDecimal(config.amountColumn()) : null;
        LocalDate date = StandardQueryConfig.present(config.dateColumn()) ? rs.getObject(config.dateColumn(), LocalDate.class) : null;
        // 事实模型里允许空值，Map.of 不接受 null
        Map<String, Object> facts = new HashMap<>();
        for (StandardQueryConfig.FieldSpec f : config.fields()) {
            facts.put(f.name(), read(rs, f.column(), f.type()));
        }
        for (StandardQueryConfig.DerivedSpec d : config.derived()) {
            facts.put(d.name(), derive(rs, d, today));
        }
        return new FactRow(rs.getString(config.idColumn()), rs.getString(config.docNoColumn()),
                rs.getString(config.companyColumn()), label, amount, date, facts);
    }

    private Object derive(ResultSet rs, StandardQueryConfig.DerivedSpec d, LocalDate today) throws SQLException {
        if ("DISPATCHED".equals(d.kind())) {
            return StandardQueryConfig.sameValue(rs.getObject(config.statusColumn()), config.dispatchedValue());
        }
        LocalDate date = rs.getObject(d.column(), LocalDate.class);
        return switch (d.kind()) {
            case "DAYS_SINCE" -> date == null ? 0L : ChronoUnit.DAYS.between(date, today);
            case "DAYS_UNTIL" -> date == null ? 0L : ChronoUnit.DAYS.between(today, date);
            default -> date != null && date.isBefore(today);
        };
    }

    private static Object read(ResultSet rs, String column, String type) throws SQLException {
        return switch (type) {
            case "decimal" -> rs.getBigDecimal(column);
            case "long" -> {
                long v = rs.getLong(column);
                yield rs.wasNull() ? null : v;
            }
            case "integer" -> {
                int v = rs.getInt(column);
                yield rs.wasNull() ? null : v;
            }
            case "boolean" -> {
                boolean v = rs.getBoolean(column);
                yield rs.wasNull() ? null : v;
            }
            case "date" -> {
                LocalDate v = rs.getObject(column, LocalDate.class);
                yield v == null ? null : v.toString();
            }
            default -> rs.getString(column);
        };
    }

    private static void addIfPresent(Set<String> columns, String column) {
        if (StandardQueryConfig.present(column)) {
            columns.add(column);
        }
    }

    /** 标识符已按白名单校验，这里只负责加反引号；schema.table 分段加 */
    static String quote(String identifier) {
        StringBuilder sb = new StringBuilder();
        for (String part : identifier.split("\\.")) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append('`').append(part).append('`');
        }
        return sb.toString();
    }
}
