package com.example.report.analysis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 执行配置按Schema含义生成摘要，避免JVM集合遍历顺序变化导致排队任务误失效；实际约束变化仍拒绝沿用旧版本。 */
class AnalysisSchemaFingerprintTest {
    @Test void objectKeyOrderAndWhitespaceDoNotChangeTheExecutionSchemaFingerprint() {
        String first="{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":300}},\"additionalProperties\":false}";
        String reordered=" { \"additionalProperties\":false, \"properties\":{\"query\":{\"maxLength\":300.0,\"type\":\"string\"}},\"type\":\"object\" } ";
        assertEquals(AnalysisContract.schemaHash(first),AnalysisContract.schemaHash(reordered));
        assertNotEquals(AnalysisContract.schemaHash(first),AnalysisContract.schemaHash(first.replace("300","301")));
    }
    @Test void arrayOrderTypeAndDuplicateFieldsRetainTheirContractMeaning() {
        assertNotEquals(AnalysisContract.schemaHash("{\"enum\":[\"a\",\"b\"]}"),AnalysisContract.schemaHash("{\"enum\":[\"b\",\"a\"]}"));
        assertNotEquals(AnalysisContract.schemaHash("{\"default\":1}"),AnalysisContract.schemaHash("{\"default\":\"1\"}"));
        assertThrows(IllegalArgumentException.class,()->AnalysisContract.schemaHash("{\"type\":\"string\",\"type\":\"object\"}"));
    }
}
