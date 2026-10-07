package com.example.report.assistant;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 确定性业务查询的独立边界用例：精确金额、稳定分页、完整统计、空值、协议注入和多币种，不代表真实模型验收。 */
class BusinessQueryEngineTest {
    final List<FieldInfo> fields=BusinessFields.forDomain(BusinessQuery.Domain.REPORT,List.of(new FieldInfo("customer","string","客户名称")));
    BusinessQuery query(List<BusinessQuery.Group> filters,String sort,boolean descending,int page,int size) {
        return new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("r1"),null,filters,sort,descending,page,size,null);
    }
    Map<String,Object> row(String id,String amount,String currency,String status) {
        var row=new LinkedHashMap<String,Object>();row.put("rowKey",id);row.put("recordId",id);row.put("amount",amount);row.put("currency",currency);row.put("status",status);row.put("companyCode","A");return row;
    }
    BusinessQuery.Group group(String field,String op,String... values){return new BusinessQuery.Group(List.of(new BusinessQuery.Filter(field,op,List.of(values))));}
    @Test void totalsDescribeAllMatchesRatherThanCurrentPageAndKeepExactDecimals() {
        var result=BusinessQueryEngine.execute(query(List.of(group("amount","GTE","0.1")),"amount",true,2,1),fields,
                List.of(row("9007199254740993","0.1","CNY","未派单"),row("9007199254740994","0.2","CNY","已派单"),row("3","9007199254740993.01","CNY","已派单")),"test");
        assertEquals(3,result.total());assertEquals("0.2",result.rows().get(0).get("amount"));assertEquals("9007199254740993.31",result.summary().amountsByCurrency().get("CNY"));
        assertEquals(2,result.summary().statusCounts().get("已派单"));
    }
    @Test void distinctCurrenciesAndMissingUnitsAreNotCombined() {
        var result=BusinessQueryEngine.execute(query(List.of(),null,false,1,20),fields,List.of(row("1","100","CNY","未派单"),row("2","50","USD","未派单"),row("3","9",null,"未派单")),"test");
        assertEquals(Map.of("CNY","100","USD","50"),result.summary().amountsByCurrency());assertEquals(1,result.summary().unclassifiedAmountCount());
    }
    @Test void nullIsNotEqualNorUnequalAndOnlyExplicitNullFilterSelectsIt() {
        var rows=List.of(row("1",null,"CNY","未派单"),row("2","2","CNY","未派单"));
        assertEquals(1,BusinessQueryEngine.execute(query(List.of(group("amount","NE","1")),null,false,1,20),fields,rows,"test").total());
        assertEquals("1",BusinessQueryEngine.execute(query(List.of(group("amount","IS_NULL")),null,false,1,20),fields,rows,"test").rows().get(0).get("rowKey"));
    }
    @Test void disjunctionAndConjunctionDoNotBroadenConditions() {
        var both=new BusinessQuery.Group(List.of(new BusinessQuery.Filter("amount","GT",List.of("100")),new BusinessQuery.Filter("status","EQ",List.of("已派单"))));
        var result=BusinessQueryEngine.execute(query(List.of(both,group("recordId","EQ","3")),null,false,1,20),fields,
                List.of(row("1","110","CNY","已派单"),row("2","120","CNY","未派单"),row("3","1","CNY","未派单")),"test");
        assertEquals(List.of("1","3"),result.rows().stream().map(r->r.get("rowKey")).toList());
    }
    @Test void emptySourceStillValidatesUnknownFieldsAndBadTypes() {
        assertThrows(ApiException.class,()->BusinessQueryEngine.execute(query(List.of(group("secret","EQ","x")),null,false,1,20),fields,List.of(),"test"));
        assertThrows(ApiException.class,()->BusinessQueryEngine.execute(query(List.of(group("amount","GT","一万")),null,false,1,20),fields,List.of(),"test"));
        assertThrows(ApiException.class,()->BusinessQueryEngine.execute(query(List.of(),"missing",false,1,20),fields,List.of(),"test"));
    }
    @Test void stableTiesAndOutOfRangePagesKeepTotal() {
        var rows=List.of(row("b","2","CNY","未派单"),row("a","2","CNY","未派单"));
        assertEquals("a",BusinessQueryEngine.execute(query(List.of(),"amount",true,1,1),fields,rows,"test").rows().get(0).get("rowKey"));
        var result=BusinessQueryEngine.execute(query(List.of(),null,false,10,1),fields,rows,"test");assertEquals(2,result.total());assertTrue(result.rows().isEmpty());
    }
    @Test void missingValuesStayLastForBothSortDirections() {
        var rows=List.of(row("missing",null,"CNY","未派单"),row("low","2","CNY","未派单"),row("high","100","CNY","未派单"));
        for(boolean descending:List.of(false,true)) {
            var result=BusinessQueryEngine.execute(query(List.of(),"amount",descending,1,20),fields,rows,"test");
            assertEquals("missing",result.rows().get(2).get("rowKey"));assertEquals(descending?"high":"low",result.rows().get(0).get("rowKey"));
        }
    }
    @Test void duplicateIdentityAndAmbiguousDetailFail() {
        var row=row("same","1","CNY","未派单");
        assertThrows(ApiException.class,()->BusinessQueryEngine.execute(query(List.of(),null,false,1,20),fields,List.of(row,row),"test"));
        var detail=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.DETAIL,List.of(),null,List.of(),null,false,1,20,null);
        assertThrows(ApiException.class,()->BusinessQueryEngine.execute(detail,fields,List.of(row,row("other","2","CNY","未派单")),"test"));
    }
    @Test void codecRejectsUnknownKeysCoercionAndTrailingJson() {
        String valid=JsonUtil.toJson(query(List.of(),null,false,1,20));
        assertEquals(1,AssistantCodec.query(JsonUtil.toMap(valid)).page());
        for(String invalid:List.of(valid.replace("\"page\":1","\"page\":1.2"),valid.replace("\"page\":1","\"page\":\"1\""),valid.replace("\"descending\":false","\"descending\":null"),valid.replace("\"page\":1,",""),valid.replace("{","{\"sql\":\"DROP TABLE x\", ")))
            assertThrows(ApiException.class,()->AssistantCodec.query(JsonUtil.toMap(invalid)));
        assertThrows(ApiException.class,()->AssistantCodec.plan("{\"route\":\"HELP\",\"query\":null,\"followUp\":false,\"clarification\":null} {}"));
    }
    @Test void malformedFilterAndPageBoundsAreRejected() {
        assertThrows(ApiException.class,()->query(List.of(group("amount","GT")),null,false,1,20));
        assertThrows(ApiException.class,()->query(List.of(group("amount","IS_NULL","1")),null,false,1,20));
        assertThrows(ApiException.class,()->query(List.of(),null,false,0,20));assertThrows(ApiException.class,()->query(List.of(),null,false,1,51));
    }
}
