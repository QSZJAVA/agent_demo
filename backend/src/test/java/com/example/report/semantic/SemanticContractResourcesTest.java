package com.example.report.semantic;

import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 防止协议资源漂移及评估数据进入提示词；固定抽象语义对照只能解释结构，不提供实际客户或测试问法。 */
class SemanticContractResourcesTest {
    @Test void fixedContrastsAlwaysFollowCurrentWireContract() throws Exception {
        try(var input=getClass().getResourceAsStream("/semantic/semantic-contrasts.json")) {
            assertNotNull(input);var examples=JsonUtil.MAPPER.readTree(input);assertEquals(3,examples.size());
            var codec=new IntentCodec();
            for(var example:examples) assertDoesNotThrow(()->codec.decode(example.get("intent").toString(),example.get("currentMessage").asText()));
        }
    }
    @Test void customerNamesAndEvaluationQuestionsRemainOutsideThePrompt() throws Exception {
        for(String corpus:java.util.List.of("generalization-holdout.json","generalization-reserve.json"))
            try(var input=getClass().getResourceAsStream("/semantic/"+corpus)) {
                assertNotNull(input);
                for(var scenario:JsonUtil.MAPPER.readTree(input)) {
                    for(var row:scenario.get("records")) if(row.has("customer"))
                        assertFalse(ModelIntentParser.INSTRUCTIONS.contains(row.get("customer").get("name").asText()),"评估客户被写入提示词");
                    for(var turn:scenario.get("turns"))
                        assertFalse(ModelIntentParser.INSTRUCTIONS.contains(turn.get("message").asText()),"评估问法被写入提示词");
                }
            }
        assertFalse(ModelIntentParser.INSTRUCTIONS.contains("天津某某贸易有限公司"));
        assertFalse(ModelIntentParser.INSTRUCTIONS.contains("北京某某咨询有限公司"));
    }
}
