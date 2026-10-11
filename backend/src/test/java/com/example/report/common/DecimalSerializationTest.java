package com.example.report.common;

import com.example.report.agent.PlanPayload;
import com.example.report.agent.PreviewPayload;
import com.example.report.catalog.query.FactRow;
import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.entity.DispatchAudit;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;
import com.example.report.entity.ExpenseReport;
import com.example.report.entity.ReceivableReport;
import com.example.report.entity.SalesReport;
import com.example.report.rule.Candidate;
import com.example.report.trace.TraceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 金额 JSON 契约回归；验证浏览器入口、卡片持久化和追溯读取，不依赖数据库或模型替身的语义结果。 */
class DecimalSerializationTest {
    private final ObjectMapper webJson = Jackson2ObjectMapperBuilder.json().build();

    @ParameterizedTest
    @ValueSource(strings = {"90071992547409.93", "9999999999999999.99", "-9999999999999999.99", "0.00", "0.01", "1E+4", "0.0000001"})
    void moneyStaysExactThroughHttpCardsAndPersistedHistory(String text) throws Exception {
        BigDecimal amount = new BigDecimal(text);
        String expected = amount.toPlainString();
        Candidate candidate = candidate(amount);
        PreviewPayload preview = new PreviewPayload("preview", "ACTIVE", 1, amount,
                List.of(new PreviewPayload.ReportCount("sales", "销售报表", 1, amount)), List.of(candidate), Map.of(), null,
                LocalDateTime.of(2026, 10, 11, 12, 0), LocalDateTime.of(2026, 10, 11, 13, 0));
        PlanPayload plan = new PlanPayload("plan", "preview", "PENDING", 1, List.of(), List.of(candidate), preview.expiresAt());

        JsonNode live = httpJson(Result.ok(preview)).path("data");
        assertText(live.path("totalAmount"), expected);
        assertText(live.path("byReport").get(0).path("amount"), expected);
        assertText(live.path("records").get(0).path("amount"), expected);
        assertTrue(live.path("total").isIntegralNumber());
        assertTrue(live.path("records").get(0).path("catalogVersion").isIntegralNumber());
        assertText(httpJson(plan).path("records").get(0).path("amount"), expected);

        // 执行真实卡片截取方法，再持久化和回读；不能仅验证发送前 DTO 的字段值。
        AgentProperties properties = new AgentProperties();
        ConversationService conversations = new ConversationService(null, null, properties, null, null);
        for (Object payload : List.of(preview, plan)) {
            Object capped = ReflectionTestUtils.invokeMethod(conversations, "capPayload", payload);
            Map<String, Object> restored = JsonUtil.toMap(JsonUtil.toJson(capped));
            assertText(httpJson(restored).path("records").get(0).path("amount"), expected);
        }
        Candidate restoredCandidate = webJson.readValue(webJson.writeValueAsString(candidate), Candidate.class);
        assertEquals(0, amount.compareTo(restoredCandidate.amount()));
    }

    @Test
    void everyPageEntityUsesTheSameMoneyContractAndKeepsCountersNumeric() throws Exception {
        BigDecimal amount = new BigDecimal("90071992547409.93");
        SalesReport sales = new SalesReport(); sales.setAmount(amount); sales.setId(21L); sales.setDispatchStatus(0);
        ReceivableReport receivable = new ReceivableReport(); receivable.setAmount(amount);
        ExpenseReport expense = new ExpenseReport(); expense.setAmount(amount);
        DispatchPreviewItem previewItem = new DispatchPreviewItem(); previewItem.setAmount(amount);
        DispatchPlanItem planItem = new DispatchPlanItem(); planItem.setAmount(amount); planItem.setAttemptCount(3); planItem.setCatalogVersion(8L);
        DispatchAudit audit = new DispatchAudit(); audit.setAmount(amount);
        FactRow fact = new FactRow("1", "SO-1", "A", "金额样本", amount, LocalDate.of(2026, 10, 11), Map.of("amount", amount));
        for (Object row : List.of(sales, receivable, expense, previewItem, planItem, audit, fact)) {
            assertText(httpJson(row).path("amount"), amount.toPlainString());
            assertText(JsonUtil.MAPPER.readTree(JsonUtil.toJson(row)).path("amount"), amount.toPlainString());
        }
        assertEquals("21", httpJson(sales).path("id").textValue());
        assertTrue(httpJson(sales).path("dispatchStatus").isIntegralNumber());
        assertTrue(httpJson(planItem).path("attemptCount").isIntegralNumber());
        assertTrue(httpJson(planItem).path("catalogVersion").isIntegralNumber());
        DispatchPlan plan = new DispatchPlan(); plan.setExecutionVersion(8L);
        assertTrue(httpJson(plan).path("executionVersion").isIntegralNumber());
        DispatchPreview summary = new DispatchPreview(); summary.setTotalAmount(amount);
        assertText(httpJson(summary).path("totalAmount"), amount.toPlainString());
    }

    @Test
    void missingAmountsStayNullAndUntypedRuleFactsKeepExactNumericValues() throws Exception {
        assertTrue(httpJson(candidate(null)).path("amount").isNull());
        assertTrue(httpJson(new DispatchPlanItem()).path("amount").isNull());
        Map<String, Object> summary = JsonUtil.toMap("{\"amount\":90071992547409.93,\"count\":2,\"version\":7}");
        assertEquals(new BigDecimal("90071992547409.93"), summary.get("amount"));
        assertInstanceOf(Integer.class, summary.get("count"));
        assertInstanceOf(Integer.class, summary.get("version"));
        FactRow fact = new FactRow("1", null, "A", null, BigDecimal.ONE, null, summary);
        FactRow recovered = JsonUtil.fromJson(JsonUtil.toJson(fact), FactRow.class);
        assertEquals(new BigDecimal("90071992547409.93"), recovered.facts().get("amount"));
        assertEquals(BigDecimal.ONE, recovered.amount());
    }

    @Test
    void jdbcTraceRowsExposeStringAmountsWithoutChangingCursorOrVersions() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 11L, "amount", new BigDecimal("90071992547409.93"), "execution_version", 5L)));
        DispatchPlan plan = new DispatchPlan(); plan.setId("plan"); plan.setTenantId("demo");
        TraceReader.Page page = new TraceReader(jdbc).page(plan, "items", 0, 50);
        JsonNode row = httpJson(page).path("records").get(0);
        assertText(row.path("amount"), "90071992547409.93");
        assertTrue(row.path("id").isIntegralNumber());
        assertTrue(row.path("execution_version").isIntegralNumber());
    }

    /** 使用 MVC 和 SSE 共用的 Jackson 消息转换器，确保断言实际传输结构而非仅序列化前对象。 */
    private JsonNode httpJson(Object value) throws Exception {
        MockHttpOutputMessage output = new MockHttpOutputMessage();
        new MappingJackson2HttpMessageConverter(webJson).write(value, MediaType.APPLICATION_JSON, output);
        return webJson.readTree(output.getBodyAsString());
    }

    private static void assertText(JsonNode value, String expected) {
        assertTrue(value.isTextual(), () -> "金额必须是 JSON 字符串，实际为：" + value);
        assertEquals(expected, value.textValue());
    }

    private static Candidate candidate(BigDecimal amount) {
        return new Candidate("sales", "销售报表", "1", "SO-1", "A", "金额样本", amount,
                LocalDate.of(2026, 10, 11), 1L, "默认规则", 1, "金额规则", 7L);
    }
}
