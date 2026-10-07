package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.*;

/** 业务查询严格 JSON 解码器；原生 Schema 仅辅助模型输出，服务端仍拒绝未知字段、隐式类型转换和多余 JSON。 */
public final class AssistantCodec {
    private static final ObjectMapper MAPPER=JsonUtil.MAPPER.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private AssistantCodec() { }
    /** 解码一次模型计划，超长、缺失或损坏输出明确澄清，不将缺失 route 当作派单。 */
    public static AssistantPlan plan(String text){return decode(text,AssistantPlan.class);}
    /** 解码 HTTP MCP 的查询参数，使用与模型输出相同的类型边界。 */
    public static BusinessQuery query(Object value){return decode(JsonUtil.toJson(value),BusinessQuery.class);}
    private static <T>T decode(String text,Class<T> type) {
        if(text==null || text.length()>24000) throw new ApiException(422,"业务查询计划缺失或过长");
        try{return MAPPER.readValue(text,type);}catch(Exception e){throw new ApiException(422,"业务查询计划不符合协议，请明确查询对象和条件");}
    }
}
