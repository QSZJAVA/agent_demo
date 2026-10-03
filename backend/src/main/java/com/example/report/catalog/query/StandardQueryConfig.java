package com.example.report.catalog.query;

import com.example.report.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 标准报表的查询配置（report_definition.query_config，query_mode = STANDARD）。
 * 表名、列名只能是校验过的标识符并在拼 SQL 时加反引号，值一律走参数绑定；配置只能由管理员维护，
 * 模型和普通用户的输入永远不会进入这里。
 *
 * @param table              表名，可带一级库名：schema.table
 * @param idColumn           主键列
 * @param companyColumn      公司（组织）列，数据范围强制按它过滤
 * @param tenantColumn       必填租户列，所有查询和回写强制按登录态租户过滤
 * @param docNoColumn        单据号列
 * @param docNoLabel         单据号展示名
 * @param labelColumn        摘要列（可选）
 * @param amountColumn       金额列（可选）
 * @param dateColumn         业务日期列（可选，同时作为排序字段）
 * @param statusColumn       派单状态列
 * @param pendingValue       待派单状态值
 * @param dispatchedValue    已派单状态值
 * @param dispatchedAtColumn 派单时间列（可选，由业务派单事务回写）
 * @param fields             规则可用的事实字段
 * @param derived            派生事实字段
 */
public record StandardQueryConfig(
        String table,
        String idColumn,
        String companyColumn,
        String tenantColumn,
        String docNoColumn,
        String docNoLabel,
        String labelColumn,
        String amountColumn,
        String dateColumn,
        String statusColumn,
        Object pendingValue,
        Object dispatchedValue,
        String dispatchedAtColumn,
        List<FieldSpec> fields,
        List<DerivedSpec> derived
) {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}(\\.[A-Za-z_][A-Za-z0-9_]{0,63})?");
    private static final Set<String> TYPES = Set.of("string", "decimal", "long", "integer", "date", "boolean");
    private static final Set<String> DATE_KINDS = Set.of("DAYS_SINCE", "DAYS_UNTIL", "IS_PAST");
    private static final String KIND_DISPATCHED = "DISPATCHED";
    /** 规则表达式里的关键字、常量，不能用作字段名 */
    private static final Set<String> RESERVED = Set.of("nil", "true", "false", "lambda", "let", "fn", "if", "else",
            "elsif", "for", "while", "return", "new", "use", "try", "catch", "finally", "throw", "break", "continue", "end");

    /** 配置解析不容忍未知字段：拼错的键（例如 dateColum）必须立刻暴露，而不是静默变成“没有日期列”*/
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public StandardQueryConfig {
        fields = fields == null ? List.of() : List.copyOf(fields);
        derived = derived == null ? List.of() : List.copyOf(derived);
    }

    /**
     * 业务数据契约；字段含义及有效范围如下。
     * @param name 事实变量名，必须与规则可用字段一致
     * @param column 经验证的来源列名
     * @param type 字段或事件类型标识，取值由所属协议定义
     * @param description 业务用途或字段含义说明
     */
    public record FieldSpec(String name, String column, String type, String description) {
    }

    /**
     * @param kind DAYS_SINCE（距该日期的天数）/ DAYS_UNTIL（到该日期还有几天）/ IS_PAST（该日期已过）/ DISPATCHED（是否已派单）
     * @param name 事实变量名，必须与规则可用字段一致
     * @param column 经验证的来源列名
     * @param description 业务用途或字段含义说明
     */
    public record DerivedSpec(String name, String kind, String column, String description) {
    }

    public static StandardQueryConfig parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ApiException("标准报表必须提供查询配置");
        }
        StandardQueryConfig config;
        try {
            config = STRICT.readValue(json, StandardQueryConfig.class);
        } catch (JsonProcessingException e) {
            throw new ApiException("查询配置解析失败：" + e.getOriginalMessage());
        }
        config.validate();
        return config;
    }

    public void validate() {
        if (table == null || !TABLE.matcher(table).matches()) {
            throw new ApiException("查询配置 table 不是合法的表名：" + table);
        }
        requireIdentifier("idColumn", idColumn);
        requireIdentifier("companyColumn", companyColumn);
        requireIdentifier("docNoColumn", docNoColumn);
        requireIdentifier("statusColumn", statusColumn);
        requireIdentifier("tenantColumn", tenantColumn);
        optionalIdentifier("labelColumn", labelColumn);
        optionalIdentifier("amountColumn", amountColumn);
        optionalIdentifier("dateColumn", dateColumn);
        optionalIdentifier("dispatchedAtColumn", dispatchedAtColumn);
        requireScalar("pendingValue", pendingValue);
        requireScalar("dispatchedValue", dispatchedValue);
        if (String.valueOf(pendingValue).equals(String.valueOf(dispatchedValue))) {
            throw new ApiException("查询配置 pendingValue 与 dispatchedValue 不能相同");
        }
        Set<String> names = new HashSet<>();
        for (FieldSpec f : fields) {
            requireFactName(f.name(), names);
            requireIdentifier("fields." + f.name() + ".column", f.column());
            if (f.type() == null || !TYPES.contains(f.type())) {
                throw new ApiException("字段 " + f.name() + " 的类型必须是 " + TYPES);
            }
        }
        for (DerivedSpec d : derived) {
            requireFactName(d.name(), names);
            if (KIND_DISPATCHED.equals(d.kind())) {
                continue;
            }
            if (!DATE_KINDS.contains(d.kind())) {
                throw new ApiException("派生字段 " + d.name() + " 的 kind 必须是 DAYS_SINCE / DAYS_UNTIL / IS_PAST / DISPATCHED");
            }
            requireIdentifier("derived." + d.name() + ".column", d.column());
        }
    }

    /** 派生字段的类型，给规则编辑页展示 */
    static String derivedType(DerivedSpec d) {
        return switch (d.kind()) {
            case "DAYS_SINCE", "DAYS_UNTIL" -> "long";
            default -> "boolean";
        };
    }

    public String displayDocNoLabel() {
        return docNoLabel == null || docNoLabel.isBlank() ? "单据号" : docNoLabel;
    }

    private static void requireIdentifier(String key, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new ApiException("查询配置 " + key + " 不是合法的列名：" + value);
        }
    }

    private static void optionalIdentifier(String key, String value) {
        if (value != null && !value.isBlank()) {
            requireIdentifier(key, value);
        }
    }

    private static void requireScalar(String key, Object value) {
        if (!(value instanceof Number || value instanceof String || value instanceof Boolean)) {
            throw new ApiException("查询配置 " + key + " 必须是数字、字符串或布尔值");
        }
    }

    private static void requireFactName(String name, Set<String> seen) {
        if (name == null || !IDENTIFIER.matcher(name).matches() || RESERVED.contains(name)) {
            throw new ApiException("事实字段名不合法：" + name);
        }
        if (!seen.add(name)) {
            throw new ApiException("事实字段名重复：" + name);
        }
    }

    static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    static boolean sameValue(Object actual, Object expected) {
        return actual != null && Objects.equals(String.valueOf(actual), String.valueOf(expected));
    }
}
