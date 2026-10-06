package com.example.report.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎：BigDecimal 金额比较、字符串函数、语法校验、危险特性关闭
 */
class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private static Map<String, Object> facts(String amount, String type) {
        Map<String, Object> f = new HashMap<>();
        f.put("amount", new BigDecimal(amount));
        f.put("expenseType", type);
        f.put("daysSinceExpense", 12L);
        f.put("dispatched", false);
        return f;
    }

    @Test
    void decimalComparisonWorks() {
        assertTrue(engine.matches("amount > 20", facts("20.01", "差旅费")));
        assertFalse(engine.matches("amount > 20", facts("20.00", "差旅费")));
        assertTrue(engine.matches("amount < 20", facts("19.90", "差旅费")));
        assertTrue(engine.matches("amount > 1000 && !dispatched", facts("8600", "差旅费")));
    }

    @Test
    void stringAndListFunctionsWork() {
        // Aviator 没有 [a, b] 列表字面量，用 seq.list(...) 构造
        assertTrue(engine.matches("amount > 1000 && include(seq.list('差旅费','市场推广费'), expenseType)", facts("3000", "差旅费")));
        assertFalse(engine.matches("amount > 1000 && include(seq.list('差旅费','市场推广费'), expenseType)", facts("3000", "办公用品")));
        assertTrue(engine.matches("string.startsWith(expenseType, '差旅') && daysSinceExpense <= 30", facts("1", "差旅费")));
        assertTrue(engine.matches("expenseType == '差旅费' || expenseType == '培训费'", facts("1", "培训费")));
    }

    @Test
    void variablesAreExtracted() {
        assertEquals(java.util.Set.of("amount", "expenseType"), engine.variables("amount > 1000 && expenseType == '差旅费'"));
    }

    @Test
    void syntaxErrorIsReported() {
        assertThrows(com.example.report.common.ApiException.class, () -> engine.validate("amount > "));
        assertThrows(com.example.report.common.ApiException.class, () -> engine.validate(""));
    }

    @Test
    void statementsAndNewInstanceAreDisabled() {
        assertThrows(Exception.class, () -> engine.validate("let a = 1; a > 0"));
        assertThrows(Exception.class, () -> engine.validate("new java.io.File('x') != nil"));
        assertThrows(Exception.class, () -> engine.validate("if (amount > 1) { true } else { false }"));
    }

    @Test
    void nonBooleanResultIsAnExplicitEvaluationError() {
        assertThrows(com.example.report.common.ApiException.class, () -> engine.matches("amount + 1", facts("1", "x")));
    }
}
