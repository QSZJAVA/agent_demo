package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * 为仓库维护的表及字段补齐数据库元数据注释，不回写 V1～V20 的历史迁移。
 * <p>MODIFY COLUMN 必须携带完整定义，因此从当前连接的 SHOW CREATE TABLE 提取原定义，
 * 仅替换 COMMENT，保留类型、默认值、字符集、排序规则、生成列表达式等属性。
 * 四张演示业务表可能在 AFTER_MIGRATE 才创建：存在时升级，不存在时由初始化脚本提供注释。
 * 外部业务表不在清单内，不会被扫描或修改。注释清单随 V21 冻结，后续修改另建迁移。
 */
public class V21__Schema_comments extends BaseJavaMigration {
    private static final String RESOURCE = "/db/migration/V21__schema_comments.json";
    private static final Pattern COLUMN = Pattern.compile("^\\s*`((?:``|[^`])+)`\\s+(.+)$");

    /** 让 Flyway 能发现版本化注释清单被改写，避免后续部署静默改变历史迁移内容。 */
    @Override
    public Integer getChecksum() {
        return checksum(resource());
    }

    /** Git 在不同操作系统上可能转换换行；同一版本清单必须产生相同 Flyway 校验和。 */
    static int checksum(byte[] data) {
        CRC32 checksum = new CRC32();
        String normalized = new String(data, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        checksum.update(normalized.getBytes(StandardCharsets.UTF_8));
        return (int) checksum.getValue();
    }

    /** 每张表一次 ALTER；迁移失败由 Flyway 阻止继续启动，不吞掉缺失表或字段错误。*/
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        JsonNode tables = new ObjectMapper().readTree(resource());
        boolean backslashEscapes;
        try (var query = connection.createStatement(); var row = query.executeQuery("SELECT @@SESSION.sql_mode")) {
            row.next();
            backslashEscapes = !row.getString(1).contains("NO_BACKSLASH_ESCAPES");
        }
        var names = tables.fieldNames();
        while (names.hasNext()) {
            String table = names.next();
            JsonNode spec = tables.get(table);
            if (!exists(connection, table)) {
                if (spec.path("optional").asBoolean(false)) continue;
                throw new SQLException("注释迁移缺少应用表：" + table);
            }
            Map<String, String> definitions = definitions(connection, table);
            var changes = new ArrayList<String>();
            changes.add("COMMENT = " + literal(spec.path("comment").asText(), backslashEscapes));
            var columns = spec.path("columns").fields();
            while (columns.hasNext()) {
                var column = columns.next();
                String definition = definitions.get(column.getKey());
                if (definition == null) throw new SQLException("注释迁移缺少字段：" + table + "." + column.getKey());
                changes.add("MODIFY COLUMN " + identifier(column.getKey()) + " "
                        + withoutComment(definition, backslashEscapes) + " COMMENT "
                        + literal(column.getValue().asText(), backslashEscapes));
            }
            try (var update = connection.createStatement()) {
                update.execute("ALTER TABLE " + identifier(table) + " " + String.join(",\n", changes));
            }
        }
    }

    private static byte[] resource() {
        try (var stream = V21__Schema_comments.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("缺少 V21 注释清单");
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取 V21 注释清单", failure);
        }
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
            query.setString(1, table);
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    /** SHOW CREATE 每个字段占一行；仅取反引号开头的字段定义，忽略索引和表级约束。 */
    private static Map<String, String> definitions(Connection connection, String table) throws SQLException {
        var columns = new LinkedHashMap<String, String>();
        try (var query = connection.createStatement(); var row = query.executeQuery("SHOW CREATE TABLE " + identifier(table))) {
            row.next();
            for (String line : row.getString(2).split("\n")) {
                var match = COLUMN.matcher(line);
                if (match.matches()) {
                    String definition = match.group(2).stripTrailing();
                    if (definition.endsWith(",")) definition = definition.substring(0, definition.length() - 1);
                    columns.put(match.group(1).replace("``", "`"), definition);
                }
            }
        }
        return columns;
    }

    /**
     * 仅识别引号外的 COMMENT 属性；默认值或表达式中的字符串即使包含 COMMENT 也原样保留。
     * 同时处理双写引号和当前连接的反斜杠转义模式，避免损坏存量列定义。
     */
    static String withoutComment(String definition, boolean backslashEscapes) throws SQLException {
        for (int i = 0; i < definition.length();) {
            char c = definition.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = quotedEnd(definition, i, backslashEscapes);
            } else if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < definition.length() && (Character.isLetterOrDigit(definition.charAt(end)) || definition.charAt(end) == '_')) end++;
                if (definition.substring(i, end).equalsIgnoreCase("COMMENT")) {
                    int value = end;
                    while (value < definition.length() && Character.isWhitespace(definition.charAt(value))) value++;
                    if (value >= definition.length() || definition.charAt(value) != '\'') throw new SQLException("无法识别原字段 COMMENT 属性");
                    return (definition.substring(0, i) + definition.substring(quotedEnd(definition, value, backslashEscapes))).strip();
                }
                i = end;
            } else i++;
        }
        return definition;
    }

    private static int quotedEnd(String sql, int start, boolean backslashEscapes) throws SQLException {
        char quote = sql.charAt(start);
        for (int i = start + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\\' && backslashEscapes && quote != '`') { i++; continue; }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) { i++; continue; }
                return i + 1;
            }
        }
        throw new SQLException("原字段定义含未闭合的引号");
    }

    private static String identifier(String name) { return "`" + name.replace("`", "``") + "`"; }
    private static String literal(String value, boolean backslashEscapes) {
        String escaped = backslashEscapes ? value.replace("\\", "\\\\") : value;
        return "'" + escaped.replace("'", "''") + "'";
    }
}
