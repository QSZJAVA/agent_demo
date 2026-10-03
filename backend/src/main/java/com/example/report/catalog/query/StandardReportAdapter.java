package com.example.report.catalog.query;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.ResultSetMetaData;
import java.sql.Types;
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
import java.util.Locale;
import com.example.report.common.ApiException;
import org.springframework.jdbc.core.ConnectionCallback;

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
        return pendingRowsPage(tenantId, companies, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<FactRow> pendingRowsPage(String tenantId, Set<String> companies, int offset, int size) {
        return pendingRowsPageWithRule(tenantId, companies, offset, size, null);
    }

    @Override
    public List<FactRow> pendingRowsPageWithRule(String tenantId, Set<String> companies,
                                                 int offset, int size, String expression) {
        return queryPending(tenantId, companies, offset, size, expression, null, false);
    }

    @Override
    public List<FactRow> pendingRowsAfterWithRule(String tenantId, Set<String> companies,
                                                  String afterId, int size, String expression) {
        return queryPending(tenantId, companies, 0, size, expression, afterId, true);
    }

    /**
     * 构建参数化且带租户、公司范围的有界待派单查询；游标和分页使用已验证的标识列，不接受用户输入的 SQL 标识符。
     */
    private List<FactRow> queryPending(String tenantId, Set<String> companies, int offset, int size,
                                       String expression, String afterId, boolean cursor) {
        if (companies == null || companies.isEmpty() || !tenantUsable(tenantId)) {
            return List.of();
        }
        requireUniqueIdentity();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("companies", companies)
                .addValue("pending", config.pendingValue());
        StringBuilder sql = new StringBuilder(selectFrom)
                .append(" WHERE ").append(quote(config.companyColumn())).append(" IN (:companies)")
                .append(" AND ").append(quote(config.statusColumn())).append(" = :pending");
        appendTenant(sql, params, tenantId);
        if (afterId != null) {
            sql.append(" AND ").append(quote(config.idColumn())).append(" > :afterId");
            params.addValue("afterId", afterId);
        }
        RuleSqlPredicate.compile(config, expression, expression == null ? Set.of() : exactNumericFields()).ifPresent(predicate -> {
            sql.append(" AND (").append(predicate.sql()).append(')');
            for (int i = 0; i < predicate.values().size(); i++) {
                params.addValue("rule" + i, predicate.values().get(i));
            }
        });
        sql.append(cursor ? " ORDER BY " + quote(config.idColumn()) : orderBy);
        if (size != Integer.MAX_VALUE) {
            sql.append(" LIMIT :pageSize");
            params.addValue("pageSize", size);
            if (!cursor) {
                sql.append(" OFFSET :pageOffset");
                params.addValue("pageOffset", offset);
            }
        }
        LocalDate today = LocalDate.now();
        return jdbc.query(sql.toString(), params, (rs, i) -> mapRow(rs, today));
    }

    @Override
    public List<FactRow> rowsByIds(String tenantId, Collection<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty() || !tenantUsable(tenantId)) {
            return List.of();
        }
        requireUniqueIdentity();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ids", recordIds);
        StringBuilder sql = new StringBuilder(selectFrom)
                .append(" WHERE ").append(quote(config.idColumn())).append(" IN (:ids)");
        appendTenant(sql, params, tenantId);
        sql.append(orderBy);
        LocalDate today = LocalDate.now();
        return jdbc.query(sql.toString(), params, (rs, i) -> mapRow(rs, today));
    }

    @Override
    public List<FactRow> pendingRowsByIds(String tenantId, Collection<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty() || !tenantUsable(tenantId)) return List.of();
        requireUniqueIdentity();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", recordIds).addValue("pending", config.pendingValue());
        StringBuilder sql = new StringBuilder(selectFrom)
                .append(" WHERE ").append(quote(config.idColumn())).append(" IN (:ids)")
                .append(" AND ").append(quote(config.statusColumn())).append(" = :pending");
        appendTenant(sql, params, tenantId);
        return jdbc.query(sql.toString(), params, (rs, i) -> mapRow(rs, LocalDate.now()));
    }

    @Override
    public List<FactRow> dryRunRowsAfter(String tenantId, Set<String> companies, String afterId, int size) {
        return pendingRowsAfterWithRule(tenantId, companies, afterId, size, null);
    }

    /**
     * 在业务事务内锁定来源记录，执行调用方复核后仅更新尚未派单的记录；锁覆盖检查与写入，消除先查后改的竞争窗口。
     */
    @Override
    public boolean markDispatchedGuarded(String tenantId, String recordId, String companyCode,
                                         LocalDateTime dispatchedAt, java.util.function.Predicate<FactRow> eligible) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("原子派单复核必须在数据库事务内执行");
        }
        if (recordId == null || companyCode == null || !tenantUsable(tenantId)) return false;
        requireUniqueIdentity();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", recordId)
                .addValue("company", companyCode).addValue("pending", config.pendingValue());
        StringBuilder sql = new StringBuilder(selectFrom).append(" WHERE ").append(quote(config.idColumn()))
                .append(" = :id AND ").append(quote(config.companyColumn())).append(" = :company AND ")
                .append(quote(config.statusColumn())).append(" = :pending");
        appendTenant(sql, params, tenantId);
        sql.append(" FOR UPDATE");
        List<FactRow> rows = jdbc.query(sql.toString(), params, (rs, i) -> mapRow(rs, LocalDate.now()));
        if (rows.size() != 1 || !eligible.test(rows.get(0))) return false;
        // The FOR UPDATE row lock remains held until the gateway transaction commits.
        return writeDispatched(tenantId, recordId, companyCode, dispatchedAt);
    }

    @Override
    public boolean markDispatched(String tenantId, String recordId, LocalDateTime dispatchedAt) {
        if (recordId == null || !tenantUsable(tenantId)) {
            return false;
        }
        requireWriteTransaction();
        requireUniqueIdentity();
        return writeDispatched(tenantId, recordId, null, dispatchedAt);
    }

    private static void requireWriteTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("派单回写必须在数据库事务内执行");
        }
    }

    private boolean writeDispatched(String tenantId, String recordId, String companyCode, LocalDateTime dispatchedAt) {
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
        if (companyCode != null) {
            sql.append(" AND ").append(quote(config.companyColumn())).append(" = :company");
            params.addValue("company", companyCode);
        }
        int changed = jdbc.update(sql.toString(), params);
        if (changed > 1) throw new IllegalStateException("派单记录标识不唯一，事务必须回滚");
        return changed == 1;
    }

    /** 发布前探测：按配置跑一条不返回数据的查询，表名、列名写错会在这里以 SQL 异常暴露 */
    public void probe() {
        requireUniqueIdentity();
    }

    /**
     * RecordKey is (reportId, recordId): company-scoped IDs cannot be represented safely.
     * Check at publish AND use, so previously published configurations and later schema changes fail closed.
     * During a dispatch transaction the zero-row SELECT holds a metadata lock through the write.  * 发布前确认记录标识在租户内唯一；不唯一会导致分页、记录复核和派单更新误关联，因此配置探测必须失败。
     */
    private void requireUniqueIdentity() {
        Boolean unique = jdbc.getJdbcTemplate().execute((ConnectionCallback<Boolean>) connection -> {
            boolean nonNullId = false;
            try (var statement = connection.prepareStatement(selectFrom + " WHERE 1 = 0")) {
                org.springframework.jdbc.datasource.DataSourceUtils.applyTimeout(statement, jdbc.getJdbcTemplate().getDataSource(),
                        jdbc.getJdbcTemplate().getQueryTimeout());
                try (var rows = statement.executeQuery()) {
                    var metadata = rows.getMetaData();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        if (config.idColumn().equalsIgnoreCase(metadata.getColumnLabel(i))) {
                            nonNullId = metadata.isNullable(i) == ResultSetMetaData.columnNoNulls;
                        }
                    }
                }
            }
            if (!nonNullId) return false;
            String[] table = config.table().split("\\.");
            String database = table.length == 2 ? table[0] : connection.getCatalog();
            Map<String, Set<String>> indexes = new HashMap<>();
            try (var statement = connection.prepareStatement("SELECT INDEX_NAME,COLUMN_NAME FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND NON_UNIQUE=0")) {
                statement.setString(1, database); statement.setString(2, table[table.length - 1]);
                org.springframework.jdbc.datasource.DataSourceUtils.applyTimeout(statement, jdbc.getJdbcTemplate().getDataSource(),
                        jdbc.getJdbcTemplate().getQueryTimeout());
                try (var keys = statement.executeQuery()) {
                    while (keys.next()) {
                        String name = keys.getString("INDEX_NAME");
                        String column = keys.getString("COLUMN_NAME");
                        indexes.computeIfAbsent(name, ignored -> new LinkedHashSet<>())
                                .add(column == null ? "<expression>" : column.toLowerCase(Locale.ROOT));
                    }
                }
            }
            Set<String> id = Set.of(config.idColumn().toLowerCase(Locale.ROOT));
            Set<String> tenantId = new LinkedHashSet<>(id);
            tenantId.add(config.tenantColumn().toLowerCase(Locale.ROOT));
            return indexes.values().stream().anyMatch(columns -> columns.equals(id) || columns.equals(tenantId));
        });
        if (!Boolean.TRUE.equals(unique)) throw new ApiException("标准报表 idColumn 必须非空，并有 ID 或租户+ID 唯一约束；其他复合主键请配置专用适配器");
    }

    /**
     * Read metadata for this query, so an external schema change cannot leave a cached proof stale.
     * Unverified or lossy mappings still work, but their predicates are evaluated only in Java.
     */
    private Set<String> exactNumericFields() {
        Set<String> verified = jdbc.query(selectFrom + " WHERE 1 = 0", new MapSqlParameterSource(),
                (org.springframework.jdbc.core.ResultSetExtractor<Set<String>>) rs -> {
                    ResultSetMetaData metadata = rs.getMetaData();
                    Set<String> result = new LinkedHashSet<>();
                    for (var field : config.fields()) {
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            if (field.column().equalsIgnoreCase(metadata.getColumnLabel(i))
                                    && exactNumericMapping(field.type(), metadata.getColumnType(i), metadata.isSigned(i))) {
                                result.add(field.name());
                    }
                }
            }
                    return Set.copyOf(result);
        });
        return verified == null ? Set.of() : verified;
    }

    static boolean exactNumericMapping(String target, int source, boolean signed) {
        boolean smallInteger = source == Types.TINYINT || source == Types.SMALLINT;
        boolean integer = smallInteger || source == Types.INTEGER;
        return switch (target) {
            case "decimal" -> integer || source == Types.BIGINT || source == Types.DECIMAL || source == Types.NUMERIC;
            case "long" -> integer || (source == Types.BIGINT && signed);
            case "integer" -> smallInteger || (source == Types.INTEGER && signed);
            default -> false;
        };
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

    /** 标识符已按白名单校验，这里只负责加反引号；schema.table 分段加*/
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
