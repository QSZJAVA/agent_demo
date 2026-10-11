package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 调查配置和来源摘要的精确 JSON 等价回归；表示差异可消除，真实值、类型和顺序差异不得被弱化。 */
class InvestigationJsonTest {
    @Test
    void equalNumberRepresentationsAndScalesHaveTheSameTextAndHash() {
        String expected=InvestigationJson.canonical(Map.of("number",1));
        for(Number number:List.<Number>of((byte)1,(short)1,1,1L,BigInteger.ONE,1.0d,1.0f,
                new BigDecimal("1.000"),new BigDecimal("1E+0"))) {
            assertEquals(expected,InvestigationJson.canonical(Map.of("number",number)),number.getClass().getSimpleName());
            assertEquals(InvestigationJson.hash(Map.of("number",1)),InvestigationJson.hash(Map.of("number",number)));
        }
        for(Number zero:List.<Number>of(0,-0.0d,-0.0f,new BigDecimal("-0.000")))
            assertEquals(InvestigationJson.canonical(Map.of("number",0)),InvestigationJson.canonical(Map.of("number",zero)));
        assertEquals(InvestigationJson.canonical(Map.of("number",1000)),InvestigationJson.canonical(Map.of("number",new BigDecimal("1E+3"))));
    }

    @Test
    void nestedKeyReorderingAndLosslessJsonRoundTripsKeepTheSameFingerprint() {
        var price=new BigDecimal("90071992547409.93000");
        var original=new LinkedHashMap<String,Object>();
        original.put("model","same");original.put("budgets",Map.of("calls",8,"price",price));
        original.put("options",List.of(true,"1",new BigDecimal("0.0100")));
        var reordered=JsonUtil.toMap("{\"options\":[true,\"1\",0.01],\"budgets\":{\"price\":90071992547409.93,\"calls\":8.0},\"model\":\"same\"}");
        assertEquals(InvestigationJson.hash(original),InvestigationJson.hash(reordered));
        assertEquals(InvestigationJson.hash(original),InvestigationJson.hash(JsonUtil.toMap(JsonUtil.toJson(original))));
        assertEquals(5,price.scale());
        assertEquals(List.of("model","budgets","options"),List.copyOf(original.keySet()));
    }

    @Test
    void numericJsonNodesUseTheSameRulesWithoutLosingLargeOrTinyFractions() throws Exception {
        for(String number:List.of("9007199254740993.01","0.000000000000000000000000000001","-9999999999999999.99")) {
            var decimal=new BigDecimal(number);
            var tree=JsonUtil.MAPPER.readTree("{\"price\":"+number+"00}");
            assertEquals(InvestigationJson.canonical(Map.of("price",decimal)),InvestigationJson.canonical(tree));
            assertNotEquals(InvestigationJson.hash(Map.of("price",decimal)),
                    InvestigationJson.hash(Map.of("price",decimal.add(BigDecimal.ONE.scaleByPowerOfTen(-decimal.scale())))));
        }
    }

    @Test
    void actualValuesBooleansStringsNullsAndArrayOrderingRemainDistinct() {
        assertNotEquals(InvestigationJson.hash(Map.of("value",new BigDecimal("90071992547409.93"))),
                InvestigationJson.hash(Map.of("value",new BigDecimal("90071992547409.94"))));
        assertNotEquals(InvestigationJson.hash(Map.of("value",true)),InvestigationJson.hash(Map.of("value",1)));
        assertNotEquals(InvestigationJson.hash(Map.of("value",false)),InvestigationJson.hash(Map.of("value",0)));
        assertNotEquals(InvestigationJson.hash(Map.of("value","1")),InvestigationJson.hash(Map.of("value",1)));
        assertNotEquals(InvestigationJson.hash(Map.of("value","1.00")),InvestigationJson.hash(Map.of("value","1.0")));
        assertNotEquals(InvestigationJson.hash(Map.of("value",List.of(1,2))),InvestigationJson.hash(Map.of("value",List.of(2,1))));
        var nullable=new LinkedHashMap<String,Object>();nullable.put("value",null);
        assertNotEquals(InvestigationJson.hash(nullable),InvestigationJson.hash(Map.of()));
    }

    @Test
    void nonFiniteNumbersAreRejectedInsteadOfCollidingWithStrings() {
        for(Number value:List.<Number>of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Float.NaN))
            assertThrows(IllegalStateException.class,()->InvestigationJson.canonical(Map.of("value",value)));
    }
}
