package com.example.report;

import com.example.report.agent.DispatchTools;
import com.example.report.agent.ToolContextKeys;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.DispatchResultPayload;
import com.example.report.dispatch.DispatchService;
import com.example.report.dispatch.PreviewCommand;
import com.example.report.dispatch.PreviewService;
import com.example.report.entity.AgentConversation;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.rule.RuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试（需要 MySQL 与 Redis）：
 * 运行方式：DEMO_IT=true DB_PASSWORD=xxx mvn test（启动时 Flyway 迁移 + 演示数据重置）
 * 覆盖：预览 → 排除 → 待确认清单 → 执行 → 审计；规则变更后旧预览、旧清单失效；SSE 对话；历史记录与卡片状态；
 * 越权访问；报表选择卡片；只靠配置接入新报表；并发确认只执行一次；同会话并发预览只有一份有效；链路追溯。
 */
@EnabledIfEnvironmentVariable(named = "DEMO_IT", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
@SuppressWarnings("unchecked")
class DispatchAgentIntegrationTest {

    @Autowired
    DispatchTools tools;
    @Autowired
    DispatchService dispatchService;
    @Autowired
    PreviewService previewService;
    @Autowired
    ConversationService conversationService;
    @Autowired
    PermissionService permissionService;
    @Autowired
    RuleService ruleService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestRestTemplate rest;

    private static ToolContext ctx(String userId, String conversationId, List<String> uiExcludes) {
        Map<String, Object> m = new HashMap<>();
        m.put(ToolContextKeys.USER_ID, userId);
        m.put(ToolContextKeys.CONVERSATION_ID, conversationId);
        m.put(ToolContextKeys.UI_EXCLUDES, uiExcludes);
        return new ToolContext(m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static HttpHeaders headers(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PermissionService.USER_HEADER, userId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> call(HttpMethod method, String path, String userId, Object body) {
        ResponseEntity<String> response = rest.exchange(path, method,
                new HttpEntity<>(body == null ? null : JsonUtil.toJson(body), headers(userId)), String.class);
        return JsonUtil.toMap(response.getBody());
    }

    private String chat(String userId, String conversationId, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("conversationId", conversationId);
        ResponseEntity<String> sse = rest.exchange("/api/agent/chat", HttpMethod.POST,
                new HttpEntity<>(JsonUtil.toJson(body), headers(userId)), String.class);
        assertEquals(200, sse.getStatusCode().value());
        return sse.getBody();
    }

    private static String conversationId(String sse) {
        Matcher m = Pattern.compile("\"conversationId\":\"([0-9a-f]{32})\"").matcher(sse);
        assertTrue(m.find(), "SSE 首个事件应带 conversationId");
        return m.group(1);
    }

    private static String firstMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), regex + " 未出现在：" + text);
        return m.group(1);
    }

    private Map<String, Object> awaitChatPreview(String userId, String sse) {
        String jobId = firstMatch(sse, "\"jobId\":\"([0-9a-f]{32})\"");
        Map<String, Object> job = Map.of();
        for (int i = 0; i < 100; i++) {
            job = asMap(call(HttpMethod.GET, "/api/dispatch/previews/jobs/" + jobId, userId, null).get("data"));
            if (!List.of("QUEUED", "RUNNING").contains(job.get("status"))) break;
            sleep(100);
        }
        assertEquals("SUCCEEDED", job.get("status"), String.valueOf(job.get("message")));
        return asMap(call(HttpMethod.GET, "/api/dispatch/previews/" + job.get("previewId"), userId, null).get("data"));
    }

    @Test
    void previewExcludeConfirmExecute() {
        // user1 只能看 A 公司：预览只包含 A 公司命中规则的记录
        Map<String, Object> preview = asMap(tools.previewDispatchable(null, null, null, null, ctx("user1", null, List.of())));
        assertEquals("ok", preview.get("status"));
        String previewId = (String) preview.get("previewId");
        List<Map<String, Object>> byReport = (List<Map<String, Object>>) preview.get("byReport");
        for (Map<String, Object> r : byReport) {
            for (Map<String, Object> rec : (List<Map<String, Object>>) r.get("records")) {
                assertEquals("A", rec.get("companyCode"), "user1 不应看到其他公司的记录");
            }
        }
        int total = (Integer) preview.get("total");
        assertTrue(total > 0);

        // 排除一条不存在的单据号 → error
        Map<String, Object> bad = asMap(tools.dispatch(previewId, List.of("NOPE-1"), ctx("user1", null, List.of())));
        assertEquals("error", bad.get("status"));

        // 排除 SO2026002（前端取消勾选的方式）→ 待确认清单
        Map<String, Object> pending = asMap(tools.dispatch(previewId, null, ctx("user1", null, List.of("SO2026002"))));
        assertEquals("pending_confirm", pending.get("status"));
        assertEquals(total - 1, pending.get("count"));
        String planId = (String) pending.get("planId");

        // 其他用户拿不到这份清单
        assertEquals(404, assertThrows(ApiException.class,
                () -> dispatchService.confirm(permissionService.resolve("user2"), planId)).getCode());

        // 本人执行 → 全部成功，记录状态变为已派单，再预览时不再出现
        DispatchResultPayload result = dispatchService.confirm(permissionService.resolve("user1"), planId);
        assertEquals(total - 1, result.successCount());
        assertEquals(0, result.failedCount());
        Map<String, Object> again = asMap(tools.previewDispatchable(null, null, null, null, ctx("user1", null, List.of())));
        assertEquals(1, again.get("total"));

        // 审计记录能串起完整链路（P0-09）
        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM dispatch_audit WHERE plan_id = ? ORDER BY id LIMIT 1", planId);
        assertEquals("T001", audit.get("tenant_id"));
        assertEquals(previewId, audit.get("preview_id"));
        assertNotNull(audit.get("report_id"));
        assertNotNull(audit.get("plan_item_id"));
        assertNotNull(audit.get("rule_id"));
        assertNotNull(audit.get("catalog_version"));
        assertNotNull(audit.get("rule_fingerprint"));
        assertEquals(permissionService.resolve("user1").permissionVersion(), audit.get("permission_version"));
        assertEquals("SUCCESS", audit.get("outcome"));
        assertNotNull(audit.get("external_request_id"));
    }

    @Test
    void stalePreviewAndPlanAreRejectedAfterRuleChange() {
        Map<String, Object> preview = asMap(tools.previewDispatchable("费用报表", null, null, null, ctx("user2", null, List.of())));
        String previewId = (String) preview.get("previewId");
        assertNotNull(previewId);
        String planId = (String) asMap(tools.dispatch(previewId, null, ctx("user2", null, List.of()))).get("planId");

        RuleService.RuleForm form = new RuleService.RuleForm();
        form.setReportId("rpt-expense-claim");
        form.setCompanyCode("*");
        form.setExpression("amount > 999999");
        form.setDescription("测试：几乎不命中");
        CurrentUser admin = permissionService.resolve("admin");
        var draft = ruleService.saveDraft(admin, form);
        ruleService.publish(admin, draft.getId());
        try {
            Map<String, Object> rejected = asMap(tools.dispatch(previewId, null, ctx("user2", null, List.of())));
            assertEquals("error", rejected.get("status"));
            assertTrue(String.valueOf(rejected.get("message")).contains("规则已更新"), String.valueOf(rejected.get("message")));
            ApiException e = assertThrows(ApiException.class, () -> dispatchService.confirm(permissionService.resolve("user2"), planId));
            assertTrue(e.getMessage().contains("规则已更新"), e.getMessage());
            assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM dispatch_plan WHERE id = ?", String.class, planId));
            assertEquals("RULE_CHANGED", jdbc.queryForObject("SELECT status_reason FROM dispatch_preview WHERE id = ?", String.class, previewId));
        } finally {
            // 回滚到初始版本，恢复环境
            ruleService.rollback(admin, ruleService.list(admin).stream()
                    .filter(r -> "rpt-expense-claim".equals(r.getReportId()) && "*".equals(r.getCompanyCode()) && r.getVersion() == 1)
                    .findFirst().orElseThrow().getId());
        }
    }

    @Test
    void sseChatHistoryAndServerSideCardStates() {
        String first = chat("user1", null, "查一下我有哪些可以派单");
        assertTrue(first.contains("event:conversation"));
        assertTrue(first.contains("event:preview_job"));
        assertTrue(first.contains("event:text"));
        assertTrue(first.contains("event:done"));
        String conversationId = conversationId(first);
        String firstPreview = (String) awaitChatPreview("user1", first).get("previewId");
        String second = chat("user1", conversationId, "只看费用报表");
        String secondPreview = (String) awaitChatPreview("user1", second).get("previewId");

        // 卡片状态以服务端为准：旧卡片作废、新卡片有效（刷新页面、换设备都是这个结果）
        Map<String, Object> states = (Map<String, Object>) call(HttpMethod.GET,
                "/api/agent/conversations/" + conversationId + "/card-states", "user1", null).get("data");
        Map<String, Object> previews = (Map<String, Object>) states.get("previews");
        assertEquals("SUPERSEDED", ((Map<String, Object>) previews.get(firstPreview)).get("status"));
        assertEquals("ACTIVE", ((Map<String, Object>) previews.get(secondPreview)).get("status"));

        // 对话日志异步写入，稍等后可查到历史；历史卡片带着服务端状态
        sleep(1500);
        String history = rest.exchange("/api/agent/conversations/" + conversationId + "/messages",
                HttpMethod.GET, new HttpEntity<>(headers("user1")), String.class).getBody();
        assertTrue(history.contains("\"role\":\"user\""));
        assertTrue(history.contains("\"cardType\":\"preview\""));
        assertTrue(history.contains("\"status\":\"SUPERSEDED\""));
        assertTrue(history.contains("\"status\":\"ACTIVE\""));

        String forbidden = rest.exchange("/api/agent/conversations/" + conversationId + "/messages",
                HttpMethod.GET, new HttpEntity<>(headers("user2")), String.class).getBody();
        assertTrue(forbidden.contains("\"code\":404"));
    }

    @Test
    void reportPermissionCannotBeBypassedFromAnyEntry() {
        // user3 没有应收报表权限（P0-04 / P0-08）：报表页、目录、预览接口、对话、手工派单全部表现为“不存在”
        assertEquals(404, ((Number) call(HttpMethod.GET, "/api/report/receivable", "user3", null).get("code")).intValue());
        assertEquals(404, ((Number) call(HttpMethod.GET, "/api/report-catalog/rpt-ar-invoice", "user3", null).get("code")).intValue());
        assertEquals(404, ((Number) call(HttpMethod.POST, "/api/dispatch/previews", "user3",
                Map.of("reportIds", List.of("rpt-ar-invoice"))).get("code")).intValue());
        assertEquals(404, ((Number) call(HttpMethod.POST, "/api/dispatch/direct", "user3",
                Map.of("reportType", "receivable", "ids", List.of("5"))).get("code")).intValue());
        Map<String, Object> search = (Map<String, Object>) call(HttpMethod.GET,
                "/api/report-catalog/search?q=" + java.net.URLEncoder.encode("应收报表", java.nio.charset.StandardCharsets.UTF_8),
                "user3", null).get("data");
        assertEquals("NONE", search.get("matchType"));
        String sse = chat("user3", null, "应收报表有哪些可以派单");
        assertFalse(sse.contains("event:preview"));
        assertFalse(sse.contains("rpt-ar-invoice"));
        // user3 自己有权限的报表照常可用
        assertEquals(0, ((Number) call(HttpMethod.GET, "/api/report/sales", "user3", null).get("code")).intValue());
        // 其他公司的记录不能手工派单
        assertEquals(403, ((Number) call(HttpMethod.POST, "/api/dispatch/direct", "user1",
                Map.of("reportType", "sales", "ids", List.of("5"))).get("code")).intValue());
    }

    @Test
    void ambiguousReportIsChosenOnTheCardAndPreviewedByTheServer() {
        String sse = chat("user2", null, "查客户对账");
        assertTrue(sse.contains("event:choice"));
        assertFalse(sse.contains("event:preview"), "歧义时不自动查询");
        String conversationId = conversationId(sse);
        Map<String, Object> created = (Map<String, Object>) call(HttpMethod.POST, "/api/dispatch/previews", "user2",
                Map.of("conversationId", conversationId, "reportIds", List.of("rpt-ar-invoice"))).get("data");
        assertEquals("ok", created.get("status"));
        Map<String, Object> preview = (Map<String, Object>) created.get("preview");
        assertEquals("ACTIVE", preview.get("status"));
        assertEquals(List.of("应收报表"), ((List<Map<String, Object>>) preview.get("byReport")).stream()
                .map(r -> r.get("reportName")).toList());
        sleep(1000);
        String history = rest.exchange("/api/agent/conversations/" + conversationId + "/messages",
                HttpMethod.GET, new HttpEntity<>(headers("user2")), String.class).getBody();
        assertTrue(history.contains("\"cardType\":\"choice\""));
        assertTrue(history.contains("（选择报表）应收报表"));
    }

    @Test
    void newReportCanBeOnboardedWithConfigurationOnly() {
        // P0-01 验收：登记目录 → 别名 → 发布 → 规则，不改任何代码就能识别、预览、派单
        Map<String, Object> config = new HashMap<>();
        config.put("table", "report_purchase");
        config.put("idColumn", "id");
        config.put("companyColumn", "company_code");
        config.put("tenantColumn", "tenant_id");
        config.put("docNoColumn", "po_no");
        config.put("docNoLabel", "采购单号");
        config.put("labelColumn", "supplier_name");
        config.put("amountColumn", "amount");
        config.put("dateColumn", "order_date");
        config.put("statusColumn", "dispatch_status");
        config.put("pendingValue", 0);
        config.put("dispatchedValue", 1);
        config.put("dispatchedAtColumn", "dispatched_at");
        config.put("fields", List.of(Map.of("name", "amount", "column", "amount", "type", "decimal", "description", "金额")));
        String reportId = "rpt-it-purchase-" + System.nanoTime() % 100000;
        String code = "it-purchase-" + System.nanoTime() % 100000;
        Map<String, Object> definition = new HashMap<>();
        definition.put("reportId", reportId);
        definition.put("reportCode", code);
        definition.put("reportName", "集成测试采购报表");
        definition.put("domainCode", "purchase");
        definition.put("queryMode", "STANDARD");
        definition.put("queryConfig", config);
        definition.put("dispatchEnabled", true);
        definition.put("permissionCode", "report:it-purchase");
        assertEquals(0, ((Number) call(HttpMethod.POST, "/api/report-catalog", "admin", definition).get("code")).intValue());
        assertEquals(0, ((Number) call(HttpMethod.POST, "/api/report-catalog/" + reportId + "/aliases", "admin",
                Map.of("alias", "测试采购台账", "aliasType", "COLLOQUIAL")).get("code")).intValue());
        assertEquals(0, ((Number) call(HttpMethod.POST, "/api/report-catalog/" + reportId + "/publish", "admin", null).get("code")).intValue());
        CurrentUser admin = permissionService.resolve("admin");
        RuleService.RuleForm form = new RuleService.RuleForm();
        form.setReportId(reportId);
        form.setExpression("amount > 1000");
        form.setDescription("采购金额大于 1000 元需要派单");
        var rule = ruleService.publish(admin, ruleService.saveDraft(admin, form).getId());
        try {
            String sse = chat("admin", null, "查一下测试采购台账有哪些可以派单");
            assertTrue(sse.contains("event:preview_job"), sse);
            Map<String, Object> preview = awaitChatPreview("admin", sse);
            assertTrue(JsonUtil.toJson(preview).contains("PO-2026-0001"));
            assertFalse(JsonUtil.toJson(preview).contains("PO-2026-0002"), "800 元不满足规则");
            // 没有该报表权限的用户识别不到
            assertFalse(chat("user1", null, "查一下测试采购台账有哪些可以派单").contains("event:preview"));
        } finally {
            ruleService.disable(admin, rule.getId());
            call(HttpMethod.POST, "/api/report-catalog/" + reportId + "/disable", "admin", null);
        }
    }

    @Test
    void concurrentConfirmationsDispatchEachRecordOnce() throws Exception {
        CurrentUser user2 = permissionService.resolve("user2");
        Map<String, Object> preview = asMap(tools.previewDispatchable("销售报表", null, null, null, ctx("user2", null, List.of())));
        String planId = (String) asMap(tools.dispatch((String) preview.get("previewId"), null, ctx("user2", null, List.of()))).get("planId");
        int items = jdbc.queryForObject("SELECT item_count FROM dispatch_plan WHERE id = ?", Integer.class, planId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit((Callable<Object>) () -> {
                start.await();
                try {
                    return dispatchService.confirm(user2, planId);
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        start.countDown();
        int executed = 0;
        for (Future<Object> f : futures) {
            Object r = f.get(30, TimeUnit.SECONDS);
            if (r instanceof DispatchResultPayload p && !p.replayed()) {
                executed++;
            }
        }
        pool.shutdown();
        assertEquals(1, executed, "只有一个请求真正执行");
        assertEquals(items, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_audit WHERE plan_id = ?", Integer.class, planId),
                "每条记录只派单一次、只审计一次");

        // 链路追溯：本人可查，别人不可查（P0-09）
        Map<String, Object> trace = (Map<String, Object>) call(HttpMethod.GET, "/api/dispatch/plans/" + planId + "/trace", "user2", null).get("data");
        assertEquals("EXECUTED", ((Map<String, Object>) trace.get("plan")).get("status"));
        assertEquals(items, ((List<?>) trace.get("items")).size());
        assertEquals(items, ((List<?>) trace.get("audits")).size());
        assertFalse(((List<?>) trace.get("rules")).isEmpty());
        assertNotNull(((Map<String, Object>) trace.get("preview")).get("query"));
        assertEquals(404, ((Number) call(HttpMethod.GET, "/api/dispatch/plans/" + planId + "/trace", "user1", null).get("code")).intValue());
    }

    @Test
    void concurrentPreviewsInOneConversationLeaveExactlyOneActive() throws Exception {
        CurrentUser user1 = permissionService.resolve("user1");
        AgentConversation conversation = conversationService.create(user1, "it");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit((Callable<Object>) () -> {
                start.await();
                return previewService.preview(user1, conversation.getId(),
                        new PreviewCommand(null, "api", "费用报表", null, null, null, null));
            }));
        }
        start.countDown();
        for (Future<Object> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_preview WHERE conversation_id = ?",
                Integer.class, conversation.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_preview WHERE conversation_id = ? AND status = 'ACTIVE'",
                Integer.class, conversation.getId()));
    }

    @Test
    void schemaIsManagedByFlyway() {
        assertEquals(11, jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1",
                Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM report_code_mapping", Integer.class));
    }

    @Test
    void asyncPreviewJobReportsProgressAndPagesOwnedResults() throws Exception {
        CurrentUser user = permissionService.resolve("user1");
        AgentConversation conversation = conversationService.create(user, "it");
        Map<String, Object> created = asMap(call(HttpMethod.POST, "/api/dispatch/previews/jobs", "user1",
                Map.of("conversationId", conversation.getId(), "reportQuery", "费用报表")).get("data"));
        String jobId = (String) created.get("id");
        Map<String, Object> job = created;
        for (int i = 0; i < 50 && List.of("QUEUED", "RUNNING").contains(job.get("status")); i++) {
            Thread.sleep(100);
            job = asMap(call(HttpMethod.GET, "/api/dispatch/previews/jobs/" + jobId, "user1", null).get("data"));
        }
        assertEquals("SUCCEEDED", job.get("status"), String.valueOf(job.get("message")));
        assertTrue(((Number) job.get("scannedRows")).intValue() >= 0);
        String previewId = (String) job.get("previewId");
        Map<String, Object> preview = asMap(call(HttpMethod.GET, "/api/dispatch/previews/" + previewId, "user1", null).get("data"));
        assertTrue(((List<?>) preview.get("records")).size() <= 50);
        assertTrue(((List<?>) call(HttpMethod.GET, "/api/dispatch/previews/" + previewId + "/items?page=1&size=1", "user1", null)
                .get("data")).size() <= 1);
        assertEquals(404, ((Number) call(HttpMethod.GET, "/api/dispatch/previews/jobs/" + jobId, "user2", null)
                .get("code")).intValue());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
