package com.example.report.rule;

import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchRuleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则缓存按 report_id 组织；预览记录的规则指纹只覆盖预览范围（报表 x 公司），范围外的规则变化不影响它
 */
class RuleCacheTest {

    @Test
    void readsApplyStartAndEndBoundariesWithoutSchedulerTick() {
        java.time.Clock clock = mock(java.time.Clock.class);
        when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
        java.time.Instant start = java.time.Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        RuleCache timed = new RuleCache(mapper, mock(StringRedisTemplate.class), mock(RedisMessageListenerContainer.class), clock);
        rule(1, "sales", "*", 1);
        DispatchRule override = rule(2, "sales", "A", 1);
        override.setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0, 1));
        override.setEffectiveTo(LocalDateTime.of(2026, 1, 1, 0, 0, 2));
        when(mapper.selectList(any())).thenReturn(rows);
        timed.reload();
        String before = timed.fingerprint("T001", Set.of("sales"), Set.of("A"));
        when(clock.instant()).thenReturn(start.plusSeconds(1));
        assertNotEquals(before, timed.fingerprint("T001", Set.of("sales"), Set.of("A")));
        assertEquals(2L, timed.find("T001", "sales", "A").orElseThrow().getId());
        when(clock.instant()).thenReturn(start.plusSeconds(2));
        assertEquals(1L, timed.find("T001", "sales", "A").orElseThrow().getId());
        assertEquals(before, timed.fingerprint("T001", Set.of("sales"), Set.of("A")));
    }

    @Test
    void sameReportAndCompanyKeysRemainIsolatedByTenant() {
        rule(1, "sales", "A", 1);
        rule(2, "sales", "A", 1).setTenantId("T002");
        reload();
        assertEquals(1L, cache.find("T001", "sales", "A").orElseThrow().getId());
        assertEquals(2L, cache.find("T002", "sales", "A").orElseThrow().getId());
        assertEquals(true, cache.find("T003", "sales", "A").isEmpty());
    }

    private final DispatchRuleMapper mapper = mock(DispatchRuleMapper.class);
    private final RuleCache cache = new RuleCache(mapper, mock(StringRedisTemplate.class), mock(RedisMessageListenerContainer.class));
    private final List<DispatchRule> rows = new ArrayList<>();

    private DispatchRule rule(long id, String reportId, String company, int version) {
        DispatchRule r = new DispatchRule();
        r.setTenantId("T001");
        r.setId(id);
        r.setReportId(reportId);
        r.setCompanyCode(company);
        r.setVersion(version);
        r.setStatus(DispatchRule.STATUS_PUBLISHED);
        r.setExpression("amount > 20");
        r.setName("规则" + id);
        rows.add(r);
        return r;
    }

    private void reload() {
        when(mapper.selectList(any())).thenReturn(List.copyOf(rows));
        cache.reload();
    }

    @Test
    void companySpecificRuleOverridesWildcardAndExpiredRulesAreIgnored() {
        rule(1, "rpt-sales-order", "*", 1);
        rule(2, "rpt-sales-order", "C", 1);
        DispatchRule future = rule(3, "rpt-expense-claim", "*", 1);
        future.setEffectiveFrom(LocalDateTime.now().plusDays(1));
        reload();
        assertEquals(1L, cache.find("T001", "rpt-sales-order", "A").orElseThrow().getId());
        assertEquals(2L, cache.find("T001", "rpt-sales-order", "C").orElseThrow().getId());
        assertEquals(true, cache.find("T001", "rpt-expense-claim", "A").isEmpty(), "未到生效期的规则不生效");
    }

    @Test
    void scopedFingerprintOnlyChangesForRulesInScope() {
        rule(1, "rpt-sales-order", "*", 1);
        rule(2, "rpt-sales-order", "C", 1);
        rule(3, "rpt-expense-claim", "*", 1);
        reload();
        String salesForA = cache.fingerprint("T001", List.of("rpt-sales-order"), Set.of("A"));
        String salesForC = cache.fingerprint("T001", List.of("rpt-sales-order"), Set.of("C"));

        // 别的报表的规则变了：销售报表的预览不受影响
        rows.get(2).setVersion(2);
        reload();
        assertEquals(salesForA, cache.fingerprint("T001", List.of("rpt-sales-order"), Set.of("A")));

        // C 公司专属规则变了：只影响包含 C 公司的预览
        rows.get(1).setVersion(2);
        reload();
        assertEquals(salesForA, cache.fingerprint("T001", List.of("rpt-sales-order"), Set.of("A")));
        assertNotEquals(salesForC, cache.fingerprint("T001", List.of("rpt-sales-order"), Set.of("C")));

        // 新增 A 公司专属规则：A 公司适用的规则变了
        rule(4, "rpt-sales-order", "A", 1);
        reload();
        assertNotEquals(salesForA, cache.fingerprint("T001", List.of("rpt-sales-order"), Set.of("A")));
    }

    @Test
    void fingerprintDoesNotDependOnArgumentOrder() {
        rule(1, "rpt-sales-order", "*", 1);
        rule(3, "rpt-expense-claim", "*", 1);
        reload();
        assertEquals(cache.fingerprint("T001", List.of("rpt-sales-order", "rpt-expense-claim"), List.of("A", "B")),
                cache.fingerprint("T001", List.of("rpt-expense-claim", "rpt-sales-order"), List.of("B", "A")));
    }

    @Test
    void ruleStartingByEffectiveTimeChangesFingerprintWithoutPublish() throws Exception {
        rule(1, "rpt-sales-order", "*", 1);
        DispatchRule later = rule(2, "rpt-sales-order", "A", 1);
        later.setEffectiveFrom(LocalDateTime.now().plusNanos(200_000_000));
        reload();
        String before = cache.fingerprint("T001", Set.of("rpt-sales-order"), Set.of("A"));
        cache.reloadIfBoundaryPassed();
        assertEquals(before, cache.fingerprint("T001", Set.of("rpt-sales-order"), Set.of("A")), "边界未到不重新加载");
        Thread.sleep(300);
        cache.reloadIfBoundaryPassed();
        assertEquals(2L, cache.find("T001", "rpt-sales-order", "A").orElseThrow().getId());
        assertNotEquals(before, cache.fingerprint("T001", Set.of("rpt-sales-order"), Set.of("A")),
                "规则按时间生效后，边界前生成的预览指纹不再匹配");
    }
}
