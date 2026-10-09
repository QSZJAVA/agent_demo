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
    /** 语义复核也执行完整严格解码；缺少原文要求或完整期望不能视为已经通过。 */
    public static SemanticReview review(String text){return decode(text,SemanticReview.class);}
    /** 解码 HTTP MCP 的查询参数，使用与模型输出相同的类型边界。 */
    public static BusinessQuery query(Object value){return decode(JsonUtil.toJson(value),BusinessQuery.class);}
    private static <T>T decode(String text,Class<T> type) {
        if(text==null || text.length()>24000) throw new ApiException(422,"业务查询计划缺失或过长");
        try {
            T value=MAPPER.readValue(text,type);
            // JSON null本身合法，但不是完整计划/复核对象，必须进入有界契约修正而非空指针失败。
            if(value==null)throw new ApiException(422,"必须输出完整JSON对象，不能输出null");
            return value;
        } catch(Exception e){throw new ApiException(422,"业务查询计划不符合协议："+diagnostic(e));}
    }
    /** 只反馈服务端约束和JSON字段路径，不回显模型原文或错误字段值，供有界结构修正定位问题。 */
    private static String diagnostic(Exception failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof ApiException api)return api.getMessage();
        if(failure instanceof com.fasterxml.jackson.core.JsonParseException)return "必须输出一个合法JSON对象，不含Markdown围栏或额外文本";
        if(failure instanceof JsonMappingException mapping) {
            String path=mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName).filter(java.util.Objects::nonNull)
                    .filter(n->n.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")).collect(java.util.stream.Collectors.joining("."));
            return (path.isBlank()?"JSON":path)+"的字段类型、必填字段或枚举不符合Schema；可空字段仍需显式提供";
        }
        return "字段类型或对象结构不符合Schema";
    }
}
