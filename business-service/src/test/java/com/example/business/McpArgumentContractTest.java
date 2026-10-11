package com.example.business;

import com.example.report.assistant.AssistantSchema;
import com.example.report.common.ApiException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** MCP 无损参数边界回归；覆盖整数截断、溢出、嵌套版本、精确金额及查询集合契约。 */
class McpArgumentContractTest {
    @Test void integerConversionKeepsBoundsAndRejectsFractionOverflowAndCoercion() {
        assertEquals(Long.MAX_VALUE,McpArgumentContract.integer(new BigInteger("9223372036854775807"),"version",1,Long.MAX_VALUE));
        assertEquals(1,McpArgumentContract.integer(new BigDecimal("1.000"),"version",1,Long.MAX_VALUE));
        for(Object value:List.of(new BigDecimal("1.0000000000000000000001"),new BigDecimal("1.9"),
                new BigInteger("9223372036854775808"),0,-1,"1",true,Double.NaN,Double.POSITIVE_INFINITY))
            assertThrows(ApiException.class,()->McpArgumentContract.integer(value,"version",1,Long.MAX_VALUE));
        assertThrows(ApiException.class,()->McpArgumentContract.integer(null,"version",1,Long.MAX_VALUE));
    }

    @Test void candidateVersionsCannotBeTruncatedAndMoneyMustRemainExactText() {
        var record=new LinkedHashMap<String,Object>(Map.of("reportId","rpt-sales-order","recordId","1","companyCode","A",
                "catalogVersion",1,"fields",List.of(),"amount","90071992547409.93"));
        validateRecord(record);
        record.put("amount",null);validateRecord(record);
        record.put("amount","90071992547409.93");
        for(String field:List.of("catalogVersion","ruleId","ruleVersion")) {
            record.put(field,new BigDecimal("1.1"));assertThrows(ApiException.class,()->validateRecord(record));
            record.put(field,1);
        }
        record.put("ruleVersion",new BigInteger("2147483648"));assertThrows(ApiException.class,()->validateRecord(record));
        record.put("ruleVersion",1);
        for(Object amount:List.of(12.34,"NaN","1e6",""," 1.00")) {
            record.put("amount",amount);assertThrows(ApiException.class,()->validateRecord(record));
        }
    }

    @Test void nestedUnknownFieldsAndInvalidArraysAreRejectedBeforeBusinessInvocation() {
        var query=new LinkedHashMap<String,Object>();
        query.put("domain","REPORT");query.put("view","LIST");query.put("reportIds",List.of("rpt-sales-order"));
        query.put("companyCode","A");query.put("conditions",List.of());query.put("sortField",null);
        query.put("descending",false);query.put("page",1);query.put("size",20);query.put("groupBy",null);
        var properties=Map.<String,Object>of("query",AssistantSchema.querySchema());
        McpArgumentContract.validate(properties,List.of("query"),Map.of("query",query));
        query.put("page",new BigDecimal("1.01"));
        assertThrows(ApiException.class,()->McpArgumentContract.validate(properties,List.of("query"),Map.of("query",query)));
        query.put("page",1);query.put("conditions",List.of(Map.of("allOf",List.of())));
        assertThrows(ApiException.class,()->McpArgumentContract.validate(properties,List.of("query"),Map.of("query",query)));
        query.put("conditions",List.of(Map.of("allOf",List.of(Map.of("field","amount","operator","GT","values",List.of("1"),"sql","ignored")))));
        assertThrows(ApiException.class,()->McpArgumentContract.validate(properties,List.of("query"),Map.of("query",query)));
    }

    @Test void requiredRecordIdentityRejectsNullEmptyAndBlankValues() {
        var record=new LinkedHashMap<String,Object>(Map.of("reportId","rpt-sales-order","recordId","1","companyCode","A",
                "catalogVersion",1,"fields",List.of()));
        for(String key:List.of("reportId","recordId","companyCode")) {
            Object original=record.get(key);
            for(Object value:java.util.Arrays.asList(null,"","  ")) {
                record.put(key,value);assertThrows(ApiException.class,()->validateRecord(record));
            }
            record.put(key,original);
        }
        validateRecord(record);
    }

    private static void validateRecord(Map<String,Object> record) {
        McpArgumentContract.validate(Map.of("record",McpConfiguration.candidateSchema()),List.of("record"),Map.of("record",record));
    }
}
