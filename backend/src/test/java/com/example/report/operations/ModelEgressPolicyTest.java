package com.example.report.operations;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 出站凭据阻断、普通语义不误伤、结构化字段及工具消息均覆盖；所有凭据样式仅为合成测试文本。 */
class ModelEgressPolicyTest {
    @ParameterizedTest @ValueSource(strings={"api_key=synthetic-value-for-test","API KEY: synthetic-test-value","密码是测试口令abcd","client-secret: fabricated-value","Bearer fabricated_token_value","sk-review-only-000000000000000000","-----BEGIN PRIVATE KEY-----\nsynthetic\n-----END PRIVATE KEY-----"})
    void credentialsNeverReachTheModel(String input) {
        assertThrows(ApiException.class,()->SensitiveData.modelText(input));
        assertFalse(SensitiveData.text(input).contains("synthetic"));
    }
    @ParameterizedTest @ValueSource(strings={"订单SK-123不要","金额至少96000元的保留","客户令牌科技有限公司的销售记录","密码字段是否有配置","token数量大于200的排除"})
    void ordinaryRecordsAndFieldQuestionsRemainUsable(String input) {assertDoesNotThrow(()->SensitiveData.modelText(input));}
    @Test void machineFieldsAndFieldFactValuesHaveNoOutboundBypass() {
        var data=Map.of("queryConfig",Map.of("password","arbitrary-short-value"),"fields",List.of(Map.of("name","bank_account","type","string","value","0000000000000000")),"recordId","13812345678");
        String safe=JsonUtil.toJson(SensitiveData.forModel(data));
        assertFalse(safe.contains("arbitrary-short-value"));assertFalse(safe.contains("0000000000000000"));assertFalse(safe.contains("13812345678"));
    }
    @Test void toolPairingSurvivesSanitizationWithoutSensitivePayloads() {
        var assistant=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call-1","function","lookup","{\"password\":\"fabricated\"}"))).build();
        var tool=ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call-1","lookup","{\"token\":\"fabricated\"}"))).build();
        var safe=ModelEgressPolicy.messages(List.of(assistant,tool));
        assertEquals("call-1",((AssistantMessage)safe.get(0)).getToolCalls().get(0).id());
        assertEquals("call-1",((ToolResponseMessage)safe.get(1)).getResponses().get(0).id());
        assertFalse(((ToolResponseMessage)safe.get(1)).getResponses().get(0).responseData().contains("fabricated"));
    }
    @ParameterizedTest @ValueSource(strings={"clientSecret","client-secret","access_token","refreshToken","privateKey","Authorization","密钥"})
    void structuredCredentialKeysDoNotRequireRecognizableSecretValues(String key) {
        var source=Map.of(key,"arbitraryValue42","fields",List.of(Map.of("name",key,"type","string","value","arbitraryValue43")));
        String clean=JsonUtil.toJson(SensitiveData.forModel(source));
        assertFalse(clean.contains("arbitraryValue42"));assertFalse(clean.contains("arbitraryValue43"));
    }
}
