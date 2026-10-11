package com.example.report.operations;

import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.example.report.rule.FieldFact;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 展示、持久化展示副本和模型出站的共同脱敏边界；业务机器标识仅在展示时保留。
 * 精确金额及目录声明数值按当前结构契约投影，不把其字符串表示当作联系方式；敏感字段始终优先处理。
 * 不修改原始业务对象、权限或已确认快照，未知数字文本仍走普通文本脱敏。
 */
public final class SensitiveData {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER=JsonUtil.MAPPER.copy()
            .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES,false);
    private SensitiveData() { }
    private static final Pattern PHONE = Pattern.compile("(?<![A-Za-z0-9])1[3-9]\\d{9}(?![A-Za-z0-9])");
    private static final Pattern ID = Pattern.compile("(?<![A-Za-z0-9])\\d{17}[0-9Xx](?![A-Za-z0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Set<String> PRIVATE = Set.of("phone", "mobile", "email", "idcard", "identitynumber", "bankaccount", "password", "passwd", "token", "apikey", "accesstoken", "refreshtoken", "clientsecret", "secretkey", "privatekey", "authorization", "身份证", "手机号", "银行卡号", "密码", "口令", "密钥", "令牌");
    private static final Set<String> MACHINE = Set.of("queryconfig", "expression", "rulesnapshot", "reportcode", "companycode", "idempotencykey");
    /** 当前报表、候选、汇总和无类型回读共用的金额字段；不是对所有数字字符串的豁免。 */
    private static final Set<String> MONEY = Set.of("amount", "totalamount");
    private static final Set<String> NUMERIC_TYPES = Set.of("decimal", "integer", "long");
    public static String text(String text) {
        if (text == null) return null;
        return ModelEgressPolicy.redact(EMAIL.matcher(ID.matcher(PHONE.matcher(text).replaceAll("[手机号已脱敏]")).replaceAll("[证件号已脱敏]")).replaceAll("[邮箱已脱敏]"));
    }
    /** 对当前业务结构生成独立展示副本；金额和声明标量保持类型、精度与空值，其余文本按字段及内容脱敏。 */
    public static JsonNode value(Object value) { return clean(MAPPER.valueToTree(value), "",false); }
    /** 模型专用投影；不沿用机器标识展示豁免，精确数值契约与显式敏感字段规则仍相同。 */
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
        return clean(node,key,model,null,Map.of());
    }

    /**
     * 递归投影当前JSON结构；声明类型只传给同一字段或columns对应的直接rows，不能扩散到任意子树。
     * @param scalarType 所属可信字段的声明类型；为空时仅识别固定金额字段
     * @param rowFields 当前rows对象由同层columns声明的字段类型，其他对象不继承
     */
    private static JsonNode clean(JsonNode node,String key,boolean model,String scalarType,Map<String,String> rowFields) {
        String normalized = privateKey(key);
        // 显式敏感字段先于金额及目录数值契约；把号码声明成数值也不能带出该字段。
        if (PRIVATE.contains(normalized) && !node.isNull()) return TextNode.valueOf("[已脱敏]");
        if (!model && MACHINE.contains(normalized)) return node;
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            Map<String,String> columns=rowFields(node);
            String fieldType=fieldFactType(node);
            node.fields().forEachRemaining(e -> {
                String declared=rowFields.get(e.getKey());
                // 币种合计仅对直接标量值生效；非数值内容仍递归脱敏，不能按容器名称绕过整棵对象。
                if("amountsbycurrency".equals(normalized))declared="decimal";
                if("value".equals(e.getKey()) && fieldType!=null)declared=fieldType;
                copy.set(e.getKey(),clean(e.getValue(),e.getKey(),model,declared,"rows".equals(e.getKey())?columns:Map.of()));
            });
            // FieldFact把语义字段名放在name中；显式敏感数值用null保持标量类型，不能将占位文本回转成BigDecimal/long。
            if(node.path("name").isTextual() && PRIVATE.contains(privateKey(node.path("name").asText()))
                    && copy.hasNonNull("value"))
                copy.set("value",NUMERIC_TYPES.contains(Objects.toString(fieldType,""))?NullNode.instance:TextNode.valueOf("[已脱敏]"));
            // 当前查询的机器标识值是后续精确读取的绑定条件；和RecordKey一样在持久状态中保留。
            // 模型出站仍全部脱敏，不把十八位记录号永久改成证件号占位符后再用于查询。
            if(!model && node.path("field").isTextual() && node.path("operator").isTextual() && node.path("values").isArray()
                    && Set.of("recordId","docNo","orderId","requestId","planId","reportId","counterpartyId").contains(node.path("field").asText()))
                copy.set("values",clean(node.get("values"),"recordIds",false));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonUtil.MAPPER.createArrayNode();
            // 数值契约只允许标量，错误数组不能把每个元素分别解释成已声明数值；rows仍携带自己的列契约。
            String elementType=scalarType!=null || MONEY.contains(normalized)?"string":null;
            node.forEach(n -> copy.add(clean(n,key,model,elementType,rowFields)));
            return copy;
        }
        JsonNode facts=fieldFactJson(node,normalized,model);
        if(facts!=null)return facts;
        // 金额字符串是已声明数值的传输表示；仍复用精确标量的范围校验，保留原文本而不舍入或转double。
        String declared=scalarType!=null?scalarType:MONEY.contains(normalized)?"decimal":null;
        if(numericText(node,declared))return node;
        if (node.isTextual() && (model || !normalized.endsWith("id") && !normalized.endsWith("ids")
                && !normalized.endsWith("no") && !normalized.endsWith("nos") && !normalized.endsWith("version")))
            return TextNode.valueOf(text(node.asText()));
        return node;
    }

    /** 只解释当前FieldFact三字段结构；自由文本或无效声明不能获得数值投影。 */
    private static String fieldFactType(JsonNode node) {
        if(node.size()!=3 || !node.path("name").isTextual() || !node.path("name").asText().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                || !node.path("type").isTextual() || !node.has("value")
                || !(node.path("value").isTextual() || node.path("value").isNull()))return null;
        String type=node.path("type").asText();return FieldFact.supported(type)?type:null;
    }

    /**
     * 清单实体和JDBC追溯把同一FieldFact数组存为fieldsJson字符串；仅解析此当前协议，清理后仍输出字符串。
     * 不解析任意自由文本中的JSON；异常或超界结构退回普通脱敏，不补造或转换旧快照。
     */
    private static JsonNode fieldFactJson(JsonNode node,String normalized,boolean model) {
        if(!"fieldsjson".equals(normalized) || !node.isTextual())return null;
        try {
            JsonNode fields=MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(node.textValue());
            if(fields==null || !fields.isArray() || fields.size()>128)return null;
            for(var field:fields)if(fieldFactType(field)==null)return null;
            return TextNode.valueOf(clean(fields,"fields",model).toString());
        } catch(java.io.IOException invalid) {return null;}
    }

    /** 同层columns定义当前rows字段；声明为string的金额同名列仍是文本，不能被固定金额名称覆盖。 */
    private static Map<String,String> rowFields(JsonNode node) {
        if(!node.path("columns").isArray() || !node.path("rows").isArray())return Map.of();
        var fields=new LinkedHashMap<String,String>();
        for(var field:node.path("columns")) {
            if(!field.path("name").isTextual() || !field.path("type").isTextual() || !FieldFact.supported(field.path("type").asText()))return Map.of();
            if(fields.putIfAbsent(field.path("name").asText(),field.path("type").asText())!=null)return Map.of();
        }
        return fields;
    }

    /** 声明数值仍必须通过精确类型和范围检查；失败退回普通文本脱敏，不转换或修补错误业务值。 */
    private static boolean numericText(JsonNode node,String type) {
        if(type==null || !NUMERIC_TYPES.contains(type) || !node.isTextual())return false;
        try {FieldFact.scalar(type,node.textValue());return true;}
        catch(RuntimeException invalid) {return false;}
    }
    /** 配置字段采用常见命名形式时仍识别同一敏感语义，不能因下划线或连字符泄露值。 */
    private static String privateKey(String name) {return name.replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);}
}
