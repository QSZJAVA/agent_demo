package com.example.report.common;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.openai.api.OpenAiApi;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 原生计量的缺失、真实零、部分响应和非法缓存计数回归；不调用外部接口。 */
class ModelUsageTest {
    @Test void emptyAndSyntheticSdkZerosStayUnknown(){
        for(Usage usage:new Usage[]{null,new EmptyUsage(),new DefaultUsage(null,null),new DefaultUsage(0,0,0,Map.of())}) {
            var actual=ModelUsage.read(usage);assertFalse(actual.complete());assertNull(actual.inputTokens());assertNull(actual.outputTokens());
        }
    }
    @Test void providerRecordPreservesGenuineZeroAndMissingComponents(){
        var zero=ModelUsage.read(new DefaultUsage(0,0,0,new OpenAiApi.Usage(0,0,0)));assertTrue(zero.complete());assertEquals(0,zero.inputTokens());
        var partial=ModelUsage.read(new DefaultUsage(12,null,12,new OpenAiApi.Usage(null,12,12)));assertFalse(partial.complete());assertEquals(12,partial.inputTokens());assertNull(partial.outputTokens());
    }
    @Test void corruptCountsNeverBecomeBillableEvidence(){
        var invalid=ModelUsage.read(new DefaultUsage(0,0,0,Map.of("prompt_tokens",-1,"completion_tokens",Long.MAX_VALUE)));assertFalse(invalid.complete());
        var text=ModelUsage.read(new DefaultUsage(0,0,0,Map.of("prompt_tokens","12","completion_tokens",0)));assertFalse(text.complete());
        var cached=ModelUsage.read(new DefaultUsage(10,2,12,Map.of("prompt_tokens",10,"completion_tokens",2,"prompt_tokens_details",Map.of("cached_tokens",11))));assertTrue(cached.complete());assertNull(cached.cachedInputTokens());
        var valid=ModelUsage.read(new DefaultUsage(10,2,12,new OpenAiApi.Usage(2,10,12,new OpenAiApi.Usage.PromptTokensDetails(null,4),null)));assertEquals(4,valid.cachedInputTokens());
    }
}
