package com.example.report;

import com.example.report.agent.DispatchTools;
import com.example.report.agent.ToolContextKeys;
import com.example.report.common.JsonUtil;
import com.example.report.dispatch.DispatchResultPayload;
import com.example.report.dispatch.DispatchService;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试（需要本地 MySQL 与 Redis）：
 * 运行方式：DEMO_IT=true DB_PASSWORD=xxx mvn test
 * 覆盖：预览 → 排除 → 待确认清单 → 执行 → 审计与状态；规则变更后旧快照被拒绝；SSE 对话接口；历史记录与归属校验。
 */
@EnabledIfEnvironmentVariable(named = "DEMO_IT", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
class DispatchAgentIntegrationTest {

    @Autowired
    DispatchTools tools;
    @Autowired
    DispatchService dispatchService;
    @Autowired
    PermissionService permissionService;
    @Autowired
    RuleService ruleService;
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

    @Test
    void previewExcludeConfirmExecute() {
        // user1 只能看 A 公司：预览只包含 A 公司命中规则的记录
        Map<String, Object> preview = asMap(tools.previewDispatchable(null, ctx("user1", null, List.of())));
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
        assertTrue(dispatchService.executePlanSafely(permissionService.resolve("user2"), planId).isEmpty());

        // 本人执行 → 全部成功，记录状态变为已派单，再预览时不再出现
        DispatchResultPayload result = dispatchService.executePlan(permissionService.resolve("user1"), planId);
        assertEquals(total - 1, result.successCount());
        assertEquals(0, result.failedCount());
        Map<String, Object> again = asMap(tools.previewDispatchable(null, ctx("user1", null, List.of())));
        assertEquals(1, again.get("total"));
    }

    @Test
    void stalePreviewIsRejectedAfterRuleChange() {
        Map<String, Object> preview = asMap(tools.previewDispatchable("expense", ctx("user2", null, List.of())));
        String previewId = (String) preview.get("previewId");
        assertNotNull(previewId);

        RuleService.RuleForm form = new RuleService.RuleForm();
        form.setReportType("expense");
        form.setCompanyCode("*");
        form.setExpression("amount > 999999");
        form.setDescription("测试：几乎不命中");
        var draft = ruleService.saveDraft(permissionService.resolve("admin"), form);
        ruleService.publish(permissionService.resolve("admin"), draft.getId());

        Map<String, Object> rejected = asMap(tools.dispatch(previewId, null, ctx("user2", null, List.of())));
        assertEquals("error", rejected.get("status"));
        assertTrue(String.valueOf(rejected.get("message")).contains("规则已变更"));

        // 回滚到初始版本，恢复环境
        ruleService.rollback(permissionService.resolve("admin"),
                ruleService.list().stream().filter(r -> "expense".equals(r.getReportType()) && "*".equals(r.getCompanyCode())
                        && r.getVersion() == 1).findFirst().orElseThrow().getId());
    }

    @Test
    void sseChatAndHistory() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PermissionService.USER_HEADER, "user1");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = JsonUtil.toJson(Map.of("message", "查一下我有哪些可以派单"));
        ResponseEntity<String> sse = rest.exchange("/api/agent/chat", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, sse.getStatusCode().value());
        String text = sse.getBody();
        assertNotNull(text);
        assertTrue(text.contains("event:conversation"));
        assertTrue(text.contains("event:preview"));
        assertTrue(text.contains("event:text"));
        assertTrue(text.contains("event:done"));

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"conversationId\":\"([0-9a-f]{32})\"").matcher(text);
        assertTrue(m.find(), "SSE 首个事件应带 conversationId");
        String conversationId = m.group(1);

        // 对话日志异步写入，稍等后可查到历史
        sleep(1500);
        ResponseEntity<String> history = rest.exchange("/api/agent/conversations/" + conversationId + "/messages",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertTrue(history.getBody().contains("\"role\":\"user\""));
        assertTrue(history.getBody().contains("\"cardType\":\"preview\""));

        HttpHeaders other = new HttpHeaders();
        other.set(PermissionService.USER_HEADER, "user2");
        ResponseEntity<String> forbidden = rest.exchange("/api/agent/conversations/" + conversationId + "/messages",
                HttpMethod.GET, new HttpEntity<>(other), String.class);
        assertTrue(forbidden.getBody().contains("\"code\":404"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
