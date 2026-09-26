package com.example.report.rule;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.ApiException;
import com.example.report.config.AgentProperties;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchRuleHistoryMapper;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.support.TestCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.example.report.support.TestCatalog.SALES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则管理的数据范围校验（P0-08）：按 ID 操作规则、新建草稿、查看列表都受公司范围约束，管理员也不例外
 */
class RuleScopeTest {

    /** 只管 A 公司的管理员 */
    private static final CurrentUser ADMIN_A = new CurrentUser("T001", "adminA", "A 公司管理员", Set.of("A"),
            Set.of(CurrentUser.ALL), true);

    private final DispatchRuleMapper ruleMapper = mock(DispatchRuleMapper.class);
    private final RuleService service = new RuleService(ruleMapper, mock(DispatchRuleHistoryMapper.class), new RuleEngine(),
            mock(RuleCache.class), mock(DispatchCandidateService.class),
            new ReportCatalogService(new TestCatalog().catalog(), new AgentProperties()));

    private static DispatchRule rule(long id, String company, String status) {
        DispatchRule r = new DispatchRule();
        r.setTenantId("T001");
        r.setId(id);
        r.setReportId(SALES);
        r.setCompanyCode(company);
        r.setVersion(1);
        r.setStatus(status);
        r.setExpression("amount > 20");
        return r;
    }

    @Test
    void anotherTenantCannotModifyRuleEvenWithSameCompanyAndAdminRole() {
        CurrentUser other = new CurrentUser("T002", "adminA", "Other", Set.of("A"), Set.of(CurrentUser.ALL), true);
        when(ruleMapper.selectById(7L)).thenReturn(rule(7, "A", DispatchRule.STATUS_DRAFT));
        assertEquals(404, assertThrows(ApiException.class, () -> service.publish(other, 7L)).getCode());
        assertEquals(404, assertThrows(ApiException.class, () -> service.deleteDraft(other, 7L)).getCode());
        RuleService.RuleForm form = new RuleService.RuleForm();
        form.setReportId(SALES);
        form.setExpression("amount > 20");
        assertThrows(ApiException.class, () -> service.saveDraft(other, form));
    }

    @Test
    void ruleOutsideCompanyScopeIsNotFoundForEveryAction() {
        when(ruleMapper.selectById(7L)).thenReturn(rule(7, "C", DispatchRule.STATUS_DRAFT));
        assertEquals(404, assertThrows(ApiException.class, () -> service.publish(ADMIN_A, 7L)).getCode());
        assertEquals(404, assertThrows(ApiException.class, () -> service.disable(ADMIN_A, 7L)).getCode());
        assertEquals(404, assertThrows(ApiException.class, () -> service.rollback(ADMIN_A, 7L)).getCode());
        assertEquals(404, assertThrows(ApiException.class, () -> service.deleteDraft(ADMIN_A, 7L)).getCode());
        verify(ruleMapper, never()).deleteById(any(Long.class));
        verify(ruleMapper, never()).updateById(any(DispatchRule.class));
    }

    @Test
    void draftForCompanyOutsideScopeIsRejected() {
        RuleService.RuleForm form = new RuleService.RuleForm();
        form.setReportId(SALES);
        form.setCompanyCode("Z");
        form.setExpression("amount > 20");
        assertEquals(403, assertThrows(ApiException.class, () -> service.saveDraft(ADMIN_A, form)).getCode());
        verify(ruleMapper, never()).insert(any(DispatchRule.class));
    }

    @Test
    void listOnlyShowsWildcardAndInScopeCompanies() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                rule(1, DispatchRule.ANY_COMPANY, DispatchRule.STATUS_PUBLISHED),
                rule(2, "A", DispatchRule.STATUS_PUBLISHED),
                rule(3, "C", DispatchRule.STATUS_PUBLISHED)));
        assertEquals(List.of(1L, 2L), service.list(ADMIN_A).stream().map(DispatchRule::getId).toList());
        assertEquals(List.of(1L), service.list(TestCatalog.USER2).stream().map(DispatchRule::getId).toList());
    }
}
