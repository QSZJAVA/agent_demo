package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 明确数值证据的程序测试；覆盖任意阈值、数制、端点、否定及不适用语境，不替代真实模型验收。 */
class NumericBoundaryEvidenceTest {
    private void verify(String text,String operator,String value,String negate) {
        NumericBoundaryEvidence.validate(operator,JsonUtil.MAPPER.valueToTree(List.of(value)),text,negate);
    }
    @ParameterizedTest
    @CsvSource({
            "一千块以上,GTE,1000,GT", "三千二百元以上,GTE,3200,GT", "至少9.6万元,GTE,96000,GT",
            "不少于十二点五零元,GTE,12.50,GT", "不低于负二十元,GTE,-20,GT", "金额>=1234.56,GTE,1234.56,GT",
            "一千元以下,LTE,1000,LT", "不超过3200元,LTE,3200,LT", "至多五千元,LTE,5000,LT",
            "超过3200元,GT,3200,GTE", "大于一万元,GT,10000,GTE", "小于680元,LT,680,LTE", "不足两千元,LT,2000,LTE",
            "amount at least 12.50,GTE,12.50,GT", "amount at most 12.50,LTE,12.50,LT", "more than 20,GT,20,GTE", "less than 20,LT,20,LTE"
    })
    void explicitOperatorsKeepTheirEqualityBoundary(String text,String correct,String value,String wrong) {
        assertDoesNotThrow(()->verify(text,correct,value,""));
        assertThrows(ApiException.class,()->verify(text,wrong,value,""));
    }
    @Test void explicitEqualityOverridesApplyToTheirOwnThreshold() {
        assertDoesNotThrow(()->verify("3200元以下，但不含3200本身","LT","3200",""));
        assertThrows(ApiException.class,()->verify("3200元以下，但不含3200本身","LTE","3200",""));
        assertDoesNotThrow(()->verify("金额大于3200，包含本数","GTE","3200",""));
        assertThrows(ApiException.class,()->verify("金额大于3200，包含本数","GT","3200",""));
    }
    @Test void rangeChecksEachThresholdAndDoesNotGuessAMissingOne() {
        String text="金额不少于680元且不高于3200元";
        assertDoesNotThrow(()->verify(text,"GTE","680",""));assertDoesNotThrow(()->verify(text,"LTE","3200",""));
        assertThrows(ApiException.class,()->verify(text,"GT","680",""));assertThrows(ApiException.class,()->verify(text,"LT","3200",""));
        assertDoesNotThrow(()->verify("比刚才更高的金额","GT","3200",""));
        assertDoesNotThrow(()->verify("日期至少从今年开始","GTE","2026-01-01",""));
    }
    @Test void wholePredicateNegationCannotBeBorrowedFromTheComparisonWord() {
        assertDoesNotThrow(()->verify("金额3200元以上的不要","LT","3200","不要"));
        assertThrows(ApiException.class,()->verify("金额3200元以上的不要","GTE","3200","不要"));
        assertThrows(ApiException.class,()->verify("金额不少于3200元","LT","3200","不"));
        assertThrows(ApiException.class,()->verify("金额3200元以上的不要","LT","3200","金额3200元以上的不要"));
        var boundaryOnly=assertThrows(ApiException.class,()->verify("3200元以下，但不含3200本身","LT","3200","不含3200本身"));
        assertTrue(boundaryOnly.getMessage().contains("negationEvidence必须为空"));
    }
    @Test void literalParsingDoesNotTruncateChineseMagnitudeOrDecimalFractions() {
        assertEquals(0,new BigDecimal("123456789.25").compareTo(NumericBoundaryEvidence.decimal("一亿二千三百四十五万六千七百八十九点二五")));
        assertEquals(0,new BigDecimal("96000").compareTo(NumericBoundaryEvidence.decimal("9.6万")));
        assertEquals(0,new BigDecimal("1000000000000").compareTo(NumericBoundaryEvidence.decimal("一万亿")));
        assertEquals(0,new BigDecimal("1234.50").compareTo(NumericBoundaryEvidence.decimal("1,234.50")));
        assertNull(NumericBoundaryEvidence.decimal("一万多"));assertNull(NumericBoundaryEvidence.decimal("2026-01-01"));
    }
}
