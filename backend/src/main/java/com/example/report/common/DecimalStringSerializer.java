package com.example.report.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 金额字段的 JSON 字符串契约；HTTP、SSE、MCP 与持久化卡片共用字段注解，避免浏览器浮点解析丢失分位。
 * 仅标注业务金额，计数、规则变量和执行版本保留原类型；空值由 Jackson 原样输出为 null。
 */
public final class DecimalStringSerializer extends JsonSerializer<BigDecimal> {
    /**
     * 将非空金额按原精度输出普通十进制字符串，不舍入、不转换 double，也不生成科学计数法。
     * @param value 当前字段的精确金额
     * @param generator 当前响应或快照的 JSON 写入器
     * @param serializers Jackson 序列化上下文
     * @throws IOException 输出流写入失败，由所属接口处理传输或持久化失败
     */
    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        generator.writeString(value.toPlainString());
    }
}
