package com.example.report.operations;

import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 展示与对话出口的脱敏工具；业务派单使用的机器标识保留原值，不能因展示脱敏改变实际写入目标。 */
public final class SensitiveData {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER=JsonUtil.MAPPER.copy()
            .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES,false);
    private SensitiveData() { }
    private static final Pattern PHONE = Pattern.compile("(?<![A-Za-z0-9])1[3-9]\\d{9}(?![A-Za-z0-9])");
    private static final Pattern ID = Pattern.compile("(?<![A-Za-z0-9])\\d{17}[0-9Xx](?![A-Za-z0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Set<String> PRIVATE = Set.of("phone", "mobile", "email", "idcard", "identitynumber", "bankaccount", "password", "passwd", "token", "apikey", "accesstoken", "refreshtoken", "clientsecret", "secretkey", "privatekey", "authorization", "身份证", "手机号", "银行卡号", "密码", "口令", "密钥", "令牌");
    private static final Set<String> MACHINE = Set.of("queryconfig", "expression", "rulesnapshot", "reportcode", "companycode", "idempotencykey");
    public static String text(String text) {
        if (text == null) return null;
        return ModelEgressPolicy.redact(EMAIL.matcher(ID.matcher(PHONE.matcher(text).replaceAll("[手机号已脱敏]")).replaceAll("[证件号已脱敏]")).replaceAll("[邮箱已脱敏]"));
    }
    public static JsonNode value(Object value) { return clean(MAPPER.valueToTree(value), "",false); }
    /** 模型专用投影；所有字段均过脱敏，不沿用业务机器标识的展示豁免。 */
    public static JsonNode forModel(Object value) { return clean(MAPPER.valueToTree(value), "",true); }
    /**
     * 单请求内的可恢复实体占位符；原始值只保留在本地，不进入模型请求。
     * @param text 脱敏后文本或待处理的文本内容
     * @param originals 受保护实体占位符到原文的本地映射；不能发给模型或写入日志
     */
    public record ModelText(String text, java.util.Map<String,String> originals) {
        public String restore(String value) {
            if (value == null) return null;
            for (var entry : originals.entrySet()) value=value.replace(entry.getKey(),entry.getValue());
            return value;
        }
    }
    public static ModelText modelText(String input) {
        ModelEgressPolicy.requireSafeText(input);
        var originals=new java.util.LinkedHashMap<String,String>();
        var tokens=new java.util.LinkedHashMap<String,String>();
        String prefix="REF"+java.util.UUID.randomUUID().toString().replace("-","")+"N";
        String masked=input;
        for (Pattern pattern : java.util.List.of(PHONE,ID,EMAIL)) {
            masked=pattern.matcher(masked).replaceAll(match -> tokens.computeIfAbsent(match.group(), raw -> {
                String token=prefix+tokens.size()+"Z"; originals.put(token,raw); return token;
            }));
        }
        return new ModelText(masked,java.util.Collections.unmodifiableMap(originals));
    }
    public static Object typed(Object value) {
        if (value == null) return null;
        Class<?> type = value instanceof java.util.Map ? java.util.Map.class : value.getClass();
        return MAPPER.convertValue(value(value), type);
    }
    private static JsonNode clean(JsonNode node, String key,boolean model) {
        String normalized = privateKey(key);
        if (PRIVATE.contains(normalized) && !node.isNull()) return TextNode.valueOf("[已脱敏]");
        if (!model && MACHINE.contains(normalized)) return node;
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), clean(e.getValue(), e.getKey(),model)));
            // FieldFact格式把业务字段名放在name中，不能只检查JSON键value。
            if(model && node.path("name").isTextual() && PRIVATE.contains(privateKey(node.path("name").asText())) && copy.has("value"))
                copy.set("value",TextNode.valueOf("[已脱敏]"));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonUtil.MAPPER.createArrayNode();
            node.forEach(n -> copy.add(clean(n, key,model)));
            return copy;
        }
        if (node.isTextual() && (model || !normalized.endsWith("id") && !normalized.endsWith("ids")
                && !normalized.endsWith("no") && !normalized.endsWith("nos") && !normalized.endsWith("version")))
            return TextNode.valueOf(text(node.asText()));
        return node;
    }
    /** 配置字段采用常见命名形式时仍识别同一敏感语义，不能因下划线或连字符泄露值。 */
    private static String privateKey(String name) {return name.replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);}
}
