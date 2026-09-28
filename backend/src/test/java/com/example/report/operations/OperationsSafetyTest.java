package com.example.report.operations;

import com.example.report.common.ApiException;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationsSafetyTest {
    @TestFactory Stream<DynamicTest> resolverCorpus() {
        return new ResolverEvaluation().evaluate(TestCatalog.demo(),0.6,0.15).cases().stream()
                .map(c->DynamicTest.dynamicTest(c.query(),()->assertTrue(c.passed(),c.toString())));
    }
    @TestFactory Stream<DynamicTest> sensitiveTextAcrossEveryChunkBoundary() {
        String raw="联系13812345678，邮箱alice.smith@example.com，证件110101199001011234，单据SO2026002。";
        String expected=SensitiveData.text(raw);
        return IntStream.rangeClosed(0,raw.length()).mapToObj(i->DynamicTest.dynamicTest("split "+i,()->{
            var stream=new SensitiveTextStream();
            assertEquals(expected,stream.feed(raw.substring(0,i))+stream.feed(raw.substring(i))+stream.flush());
        }));
    }
    @Test void nestedSensitiveFieldsAreMaskedButBusinessIdentityIsStable() {
        var value=SensitiveData.value(Map.of("rows",List.of(Map.of("phone","13812345678","email","a@b.com","idCard","secret","recordId","13812345678","docNo","SO2026002","amount",1234))));
        assertEquals("[已脱敏]",value.at("/rows/0/phone").asText());
        assertEquals("13812345678",value.at("/rows/0/recordId").asText());
        assertEquals("SO2026002",value.at("/rows/0/docNo").asText());
        assertEquals(1234,value.at("/rows/0/amount").asInt());
    }
    @Test void longUnbrokenModelTokensAreBounded() {
        var stream=new SensitiveTextStream();assertEquals("[长文本已脱敏]",stream.feed("a".repeat(100000)));assertEquals("。",stream.feed("。"));
    }
    record Amount(java.math.BigDecimal amount,String label) { }
    @Test void redactionPreservesDecimalScaleAndUnknownSensitiveDisplayColumns() {
        var original=new Amount(new java.math.BigDecimal("2000.00"),"13812345678");
        var safe=(Amount)SensitiveData.typed(original);
        assertEquals(original.amount(),safe.amount());assertFalse(safe.label().contains("13812345678"));
        assertEquals("[邮箱已脱敏]",SensitiveData.value(Map.of("customerContact","test@example.com")).get("customerContact").asText());
    }
    @Test void rolloutIsStableAcrossVersionsAndEndpoints() {
        var zero=new OperationsPolicy.Policy("catalog:rpt-sales-order",1,Map.of("percent",0));
        var full=new OperationsPolicy.Policy(zero.key(),2,Map.of("percent",100));
        assertFalse(OperationsPolicy.included(TestCatalog.USER1,zero.key(),zero));
        assertTrue(OperationsPolicy.included(TestCatalog.USER1,full.key(),full));
        var half=new OperationsPolicy.Policy(zero.key(),3,Map.of("percent",50));
        var same=new OperationsPolicy.Policy(zero.key(),4,Map.of("percent",50));
        assertEquals(OperationsPolicy.included(TestCatalog.USER1,half.key(),half),OperationsPolicy.included(TestCatalog.USER1,same.key(),same));
    }
    @TestFactory Stream<DynamicTest> malformedPoliciesCannotBeSaved() {
        return Stream.of(-1,101,0.5,Double.NaN,Double.POSITIVE_INFINITY,"100",null).map(v->DynamicTest.dynamicTest("percent "+v,()->{
            Map<String,Object> payload=new HashMap<>();payload.put("percent",v);
            assertThrows(ApiException.class,()->OperationsPolicy.validate("catalog:rpt-sales-order",payload));
        }));
    }
    @Test void resolverMarginZeroCannotSilentlyRemoveTopCandidate() {
        assertThrows(ApiException.class,()->OperationsPolicy.validate("resolver",Map.of("percent",100,"fuzzyThreshold",0.6,"ambiguityMargin",0)));
    }
    @Test void policyRejectsUnknownFieldsAndUnauthorizedActors() {
        assertThrows(ApiException.class,()->OperationsPolicy.validate("catalog:rpt-sales-order",Map.of("percent",100,"tenant","T002")));
        assertThrows(ApiException.class,()->OperationsPolicy.requireAdmin(TestCatalog.USER1));
    }
}
