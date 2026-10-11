package com.example.report.catalog.query;

import com.example.report.assistant.BusinessQuery;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 下推保守性及参数边界单元测试；真实MySQL集合等价另由P1隔离测试验证。 */
class BusinessSqlPredicateTest {
    static BusinessQuery.Filter f(String field,String operator,String...values){return new BusinessQuery.Filter(field,operator,List.of(values));}
    static BusinessQuery q(BusinessQuery.Group...groups){return new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.LIST,List.of("sales"),"A",List.of(groups),null,false,1,20,null);}
    static BusinessQuery.Group g(BusinessQuery.Filter...filters){return new BusinessQuery.Group(List.of(filters));}
    static Map<String,BusinessSqlPredicate.Column> columns(){return Map.of("amount",new BusinessSqlPredicate.Column("amount","decimal",Types.DECIMAL,true),"label",new BusinessSqlPredicate.Column("label","string",Types.VARCHAR,false),"date",new BusinessSqlPredicate.Column("biz_date","date",Types.DATE,false));}
    @Test void bindsTextInsteadOfInterpolatingSqlAndUsesLiteralContains(){var value="X%' OR 1=1 --_";var predicate=BusinessSqlPredicate.compile(q(g(f("label","CONTAINS",value))),columns());assertFalse(predicate.sql().contains(value));assertTrue(predicate.sql().contains("LOCATE(CAST(:bq0 AS BINARY),CAST(`label` AS BINARY))>0"));assertEquals(value,predicate.parameters().get("bq0"));}
    @Test void dropsWholeOrWhenOneBranchHasNoProvenPredicate(){assertEquals("",BusinessSqlPredicate.compile(q(g(f("amount","GT","20")),g(f("derived","EQ","1"))),columns()).sql());}
    @Test void keepsSafeNecessaryAndSubsetOfEveryOrBranch(){var p=BusinessSqlPredicate.compile(q(g(f("amount","GTE","20"),f("derived","EQ","2")),g(f("label","EQ","Ab "),f("amount","IS_NULL"))),columns());assertTrue(p.sql().contains(" OR "));assertTrue(p.sql().contains(" IS NULL"));assertFalse(p.sql().contains("derived"));assertEquals(new java.math.BigDecimal("20"),p.parameters().get("bq0"));}
    @Test void floatAndUnsignedLongDoNotClaimLosslessNumericMapping(){var m=Map.of("amount",new BusinessSqlPredicate.Column("x","decimal",Types.DOUBLE,true),"id",new BusinessSqlPredicate.Column("id","long",Types.BIGINT,false));assertEquals("",BusinessSqlPredicate.compile(q(g(f("amount","LT","0.1"),f("id","EQ","9223372036854775807"))),m).sql());}
    @Test void stringRangesAndTimestampToDateMappingsStayInCommonPredicate(){assertEquals("",BusinessSqlPredicate.compile(q(g(f("label","LT","😀"))),columns()).sql());assertEquals("",BusinessSqlPredicate.compile(q(g(f("date","EQ","2026-09-01"))),Map.of("date",new BusinessSqlPredicate.Column("d","date",Types.TIMESTAMP,false))).sql());}
    @Test void dateAndNullUseTypedParametersAndExplicitNullSemantics(){var p=BusinessSqlPredicate.compile(q(g(f("date","GTE","2026-09-01"),f("date","LTE","2026-09-30"),f("amount","NOT_NULL"))),columns());assertEquals(java.sql.Date.valueOf("2026-09-01"),p.parameters().get("bq0"));assertTrue(p.sql().contains("`amount` IS NOT NULL"));}
}
