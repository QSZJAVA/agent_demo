package com.example.report.common;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 从当前OpenAI兼容端点的原生响应提取真实计量；不使用SDK对缺失字段补出的零，也不推算未知费用。
 * @param inputTokens 原生prompt_tokens，缺失或非法时为null
 * @param outputTokens 原生completion_tokens，缺失或非法时为null
 * @param cachedInputTokens 原生缓存输入token数，缺失或超出输入计量时为null
 */
public record ModelUsage(Integer inputTokens,Integer outputTokens,Integer cachedInputTokens) {
    /** 输入和输出均有有效原生计量才可用于总量及费用统计；真实的零是已知计量。 */
    public boolean complete(){return inputTokens!=null && outputTokens!=null;}

    /**
     * 解析一次实际响应的计量；空响应、空SDK对象或不认识的供应商结构均保持未知，不影响业务请求结果。
     * @param usage SDK响应用量，只读取其原生证据
     * @return 有界非负计数，没有原始响应或认证信息
     */
    public static ModelUsage read(Usage usage) {
        if(usage==null || usage.getNativeUsage()==null)return new ModelUsage(null,null,null);
        try {
            JsonNode nativeUsage=JsonUtil.MAPPER.valueToTree(usage.getNativeUsage());
            Integer input=count(nativeUsage.path("prompt_tokens")),output=count(nativeUsage.path("completion_tokens"));
            Integer cached=count(nativeUsage.path("prompt_tokens_details").path("cached_tokens"));
            if(cached==null)cached=count(nativeUsage.path("prompt_cache_hit_tokens"));
            if(cached!=null && (input==null || cached>input))cached=null;
            return new ModelUsage(input,output,cached);
        } catch(RuntimeException unsupported) {return new ModelUsage(null,null,null);}
    }
    private static Integer count(JsonNode value){return value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>=0?value.intValue():null;}
}
