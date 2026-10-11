package com.example.report.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;
import java.util.UUID;

/**
 * 当前协议的 JSON 与 ID 工具：持久化快照、卡片载荷和调查日志共用严格 ObjectMapper；不接受未知旧字段。
 * 无类型映射中的小数保留 BigDecimal，禁止在快照截取或回读期间先损失精度；金额字段另按注解输出字符串。
 */
public final class JsonUtil {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private JsonUtil() {
    }

    /**
     * 外部结构化协议的独立严格解码器；除通用数值转换开关外，显式拒绝Jackson默认允许的数字/布尔转字符串。
     * @return 可按模块复用的映射器，不修改持久化快照和精确数值序列化所用的共享实例
     */
    public static ObjectMapper strictMapper() {
        var mapper=MAPPER.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        for(var shape:java.util.List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
            mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual).setCoercion(shape,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        return mapper;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("JSON对象不符合当前格式", e);
        }
    }

    /** 32 位无横线 UUID，与表中 VARCHAR(32) 主键匹配 */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
