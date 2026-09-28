package com.example.report.catalog.query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 只转换白名单字段上的简单比较和 AND；不认识的 Aviator 表达式保持 Java 求值。 */
final class RuleSqlPredicate {
    private static final Pattern COMPARISON = Pattern.compile(
            "\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(nil|true|false|-?[0-9]+(?:\\.[0-9]+)?|'[A-Za-z0-9 _-]{1,100}')\\s*");

    record Fragment(String sql, List<Object> values) { }

    static Optional<Fragment> compile(StandardQueryConfig config, String expression) {
        return compile(config, expression, Set.of());
    }

    /** Numeric fields must have an exact JDBC mapping verified against result-set metadata. */
    static Optional<Fragment> compile(StandardQueryConfig config, String expression, Set<String> exactNumericFields) {
        if (expression == null || expression.isBlank() || expression.contains("||")) return Optional.empty();
        Map<String, StandardQueryConfig.FieldSpec> fields = config.fields().stream()
                .collect(Collectors.toMap(StandardQueryConfig.FieldSpec::name, Function.identity()));
        String[] pieces = expression.split("&&", -1);
        List<String> sql = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String piece : pieces) {
            Matcher match = COMPARISON.matcher(piece);
            if (!match.matches()) return Optional.empty();
            StandardQueryConfig.FieldSpec field = fields.get(match.group(1));
            if (field == null) return Optional.empty();
            String operator = match.group(2);
            String literal = match.group(3);
            String column = StandardReportAdapter.quote(field.column());
            if ("nil".equals(literal)) {
                if (!"==".equals(operator) && !"!=".equals(operator)) return Optional.empty();
                sql.add(column + ("==".equals(operator) ? " IS NULL" : " IS NOT NULL"));
                continue;
            }
            // Java 中 null != 常量可能为 true，而 SQL 的 NULL <> 常量为 UNKNOWN；不能下推。
            if ("!=".equals(operator)) return Optional.empty();
            Object value;
            try {
                if (literal.startsWith("'")) {
                    if (!"string".equals(field.type()) && !"date".equals(field.type())) return Optional.empty();
                    value = literal.substring(1, literal.length() - 1);
                } else if ("true".equals(literal) || "false".equals(literal)) {
                    // JDBC getBoolean can map values such as TINYINT 2 to true, whereas SQL
                    // column = TRUE compares with 1. Physical type/value constraints are not
                    // verified by the catalog, so keep boolean comparisons in Java.
                    return Optional.empty();
                } else {
                    // Aviator parses fractional literals as double. Binding their original text
                    // as DECIMAL can exclude rows Aviator matches after rounding. Keep those in Java.
                    // Integral literals must fit Aviator's long and the source/JDBC mapping must be exact.
                    if (!exactNumericFields.contains(field.name()) || literal.contains(".")) return Optional.empty();
                    long integral = Long.parseLong(literal);
                    // Aviator's decimal-vs-long comparison converts the long through double.
                    if (integral < -9007199254740991L || integral > 9007199254740991L) return Optional.empty();
                    value = BigDecimal.valueOf(integral);
                }
            } catch (RuntimeException e) {
                return Optional.empty();
            }
            // Aviator 将 nil 排在非 nil 值之前：nil < x、nil <= x 为 true。
            // SQL 的 NULL 比较为 UNKNOWN，必须显式保留这些行供 Java 再次求值。
            // 字符串/日期比较还受数据库排序规则影响，不能保证与 Aviator 一致。
            if (("string".equals(field.type()) || "date".equals(field.type()))) return Optional.empty();
            String comparison = column + " " + ("==".equals(operator) ? "=" : operator)
                    + " :rule" + values.size();
            sql.add("<".equals(operator) || "<=".equals(operator)
                    ? "(" + column + " IS NULL OR " + comparison + ")" : comparison);
            values.add(value);
        }
        return Optional.of(new Fragment(String.join(" AND ", sql), List.copyOf(values)));
    }

    private RuleSqlPredicate() { }
}
