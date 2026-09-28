package com.example.report.operations;

import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Presentation and conversation boundary only; identifiers used for dispatch are never rewritten. */
public final class SensitiveData {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER=JsonUtil.MAPPER.copy()
            .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES,false);
    private SensitiveData() { }
    private static final Pattern PHONE = Pattern.compile("(?<![A-Za-z0-9])1[3-9]\\d{9}(?![A-Za-z0-9])");
    private static final Pattern ID = Pattern.compile("(?<![A-Za-z0-9])\\d{17}[0-9Xx](?![A-Za-z0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Set<String> PRIVATE = Set.of("phone", "mobile", "email", "idcard", "identitynumber", "bankaccount", "password", "token", "apikey", "身份证", "手机号", "银行卡号");
    private static final Set<String> MACHINE = Set.of("queryconfig", "expression", "rulesnapshot", "reportcode", "companycode", "idempotencykey");
    public static String text(String text) {
        if (text == null) return null;
        return EMAIL.matcher(ID.matcher(PHONE.matcher(text).replaceAll("[手机号已脱敏]")).replaceAll("[证件号已脱敏]")).replaceAll("[邮箱已脱敏]");
    }
    public static JsonNode value(Object value) { return clean(MAPPER.valueToTree(value), ""); }
    public static Object typed(Object value) {
        if (value == null) return null;
        Class<?> type = value instanceof java.util.Map ? java.util.Map.class : value.getClass();
        return MAPPER.convertValue(value(value), type);
    }
    private static JsonNode clean(JsonNode node, String key) {
        String normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
        if (PRIVATE.contains(normalized) && !node.isNull()) return TextNode.valueOf("[已脱敏]");
        if (MACHINE.contains(normalized)) return node;
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), clean(e.getValue(), e.getKey())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonUtil.MAPPER.createArrayNode();
            node.forEach(n -> copy.add(clean(n, key)));
            return copy;
        }
        if (node.isTextual() && !normalized.endsWith("id") && !normalized.endsWith("ids")
                && !normalized.endsWith("no") && !normalized.endsWith("nos") && !normalized.endsWith("version"))
            return TextNode.valueOf(text(node.asText()));
        return node;
    }
}
