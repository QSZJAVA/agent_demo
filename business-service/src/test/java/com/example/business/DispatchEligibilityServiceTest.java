package com.example.business;

import com.example.report.assistant.*;
import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.*;
import com.example.report.common.ApiException;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.RuleEngine;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 当前资格的确定性回归：真实表达式引擎、来源快照及授权边界；全程使用内存来源与映射替身，不创建数据库。 */
class DispatchEligibilityServiceTest {
    @org.junit.jupiter.api.BeforeAll static void registerMapperMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"eligibility-test"),DispatchRule.class);
    }
    private final CurrentUser user=new CurrentUser("T001","reader","查询用户",Set.of("A"),Set.of("report:sales"),false);
    private final DispatchRuleMapper rules=mock(DispatchRuleMapper.class);
    private final DispatchEligibilityService service=new DispatchEligibilityService(rules,new RuleEngine());

    private CatalogEntry report() {
        var entry=mock(CatalogEntry.class);when(entry.tenantId()).thenReturn("T001");when(entry.reportId()).thenReturn("sales");
        when(entry.reportName()).thenReturn("销售报表");when(entry.permissionCode()).thenReturn("report:sales");when(entry.dispatchEnabled()).thenReturn(true);
        when(entry.fields()).thenReturn(List.of(new FieldInfo("amount","decimal","金额"),new FieldInfo("productName","string","产品名称"),new FieldInfo("saleDate","date","销售日期")));
        return entry;
    }
    private FactRow fact(String id,String company,String amount) {
        return new FactRow(id,"SO-"+id,company,"服务器",new BigDecimal(amount),LocalDate.of(2026,1,15),
                Map.of("amount",new BigDecimal(amount),"productName","服务器","saleDate","2026-01-15"));
    }
    private DispatchRule rule(long id,String company,int version,String expression) {
        var rule=new DispatchRule();rule.setId(id);rule.setTenantId("T001");rule.setReportId("sales");rule.setCompanyCode(company);
        rule.setName("销售金额规则");rule.setStatus(DispatchRule.STATUS_PUBLISHED);rule.setVersion(version);rule.setExpression(expression);
        rule.setDescription("金额满足本公司生效标准");return rule;
    }
    private void published(DispatchRule... values) {when(rules.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(values));}

    @Test void currentRuleAndExactAmountProduceSingleRecordEvidence() {
        published(rule(7,"*",1,"amount > 20"));
        var result=service.evaluate(user,report(),fact("1","A","128000.00"),"未派单");
        assertTrue(result.eligible());assertEquals("7",result.ruleId());assertEquals(1,result.ruleVersion());
        assertEquals(1,result.checkedFields().size());assertEquals("amount",result.checkedFields().get(0).name());
        assertEquals("128000.00",result.checkedFields().get(0).value());
        assertFalse(service.evaluate(user,report(),fact("2","A","20"),"未派单").eligible());
        assertTrue(service.evaluate(user,report(),fact("3","A","20.000000000000000001"),"未派单").eligible());
        verify(rules,never()).publishedReportForUpdate(anyString(),anyString());
    }
    @Test void companyRuleWinsOverHigherWildcardVersionAndIgnoresExpiredOrForeignRules() {
        var expired=rule(8,"A",5,"amount > 0");expired.setEffectiveTo(LocalDateTime.now().minusDays(1));
        var future=rule(9,"A",6,"amount > 0");future.setEffectiveFrom(LocalDateTime.now().plusDays(1));
        var foreign=rule(10,"A",9,"amount > 0");foreign.setTenantId("T002");
        published(rule(1,"*",99,"amount > 20"),rule(2,"A",1,"amount > 100"),expired,future,foreign);
        var result=service.evaluate(user,report(),fact("1","A","50"),"未派单");
        assertFalse(result.eligible());assertEquals("2",result.ruleId());assertTrue(result.reason().contains("不满足"));
    }
    @Test void missingRuleAndAlreadyDispatchedHaveDifferentReasons() {
        published();var entry=report();
        var missing=service.evaluate(user,entry,fact("1","A","128000"),"未派单");
        assertFalse(missing.eligible());assertTrue(missing.reason().contains("没有适用的生效"));assertNull(missing.ruleId());
        clearInvocations(rules);
        var dispatched=service.evaluate(user,entry,fact("1","A","128000"),"已派单");
        assertFalse(dispatched.eligible());assertTrue(dispatched.reason().contains("已派单"));assertTrue(dispatched.checkedFields().isEmpty());
        when(entry.dispatchEnabled()).thenReturn(false);
        assertTrue(service.evaluate(user,entry,fact("1","A","128000"),"未派单").reason().contains("未启用派单"));
        verifyNoInteractions(rules);
    }
    @Test void ruleErrorsAndUnknownSourceStateAreNotNegativeQualificationResults() {
        var entry=report();
        published(rule(1,"*",1,"amount"));
        assertThrows(ApiException.class,()->service.evaluate(user,entry,fact("1","A","50"),"未派单"));
        published(rule(1,"*",1,"unconfigured == nil"));
        assertThrows(ApiException.class,()->service.evaluate(user,entry,fact("1","A","50"),"未派单"));
        assertThrows(ApiException.class,()->service.evaluate(user,entry,fact("1","A","50"),"处理中"));
        assertThrows(ApiException.class,()->service.evaluate(user,entry,fact("1","A","50"),null));
    }
    @Test void originalDateFactsKeepTheSameTypesAsDispatchEvaluation() {
        published(rule(1,"*",1,"saleDate == '2026-01-15' && amount > 20"));
        var result=service.evaluate(user,report(),fact("1","A","21"),"未派单");
        assertTrue(result.eligible());assertEquals(Set.of("saleDate","amount"),result.checkedFields().stream().map(f->f.name()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void unauthorizedCompanyTenantAndReportStopBeforeRuleRead() {
        var entry=report();
        assertThrows(ApiException.class,()->service.evaluate(user,entry,fact("1","B","50"),"未派单"));
        when(entry.tenantId()).thenReturn("T002");assertThrows(ApiException.class,()->service.evaluate(user,entry,fact("1","A","50"),"未派单"));
        when(entry.tenantId()).thenReturn("T001");when(entry.permissionCode()).thenReturn("report:expense");
        assertThrows(ApiException.class,()->service.evaluate(user,entry,fact("1","A","50"),"未派单"));verifyNoInteractions(rules);
    }
    @Test void readQueryEvaluatesOnlyTheUniqueTargetInTheSameSnapshot() {
        published(rule(1,"*",1,"amount > 20"));
        var entry=report();var queries=mock(BusinessQueries.class);var adapter=mock(ReportQueryAdapter.class);
        when(entry.adapter()).thenReturn(adapter);when(queries.catalog(user)).thenReturn(List.of(Map.of("reportId","sales")));
        when(queries.require(user,"sales",false)).thenReturn(entry);
        var rows=List.of(new ReportDataRow(fact("1","A","128000"),"已派单"),new ReportDataRow(fact("2","A","15"),"未派单"));
        when(adapter.dataRowsAfter(eq("T001"),eq(Set.of("A")),isNull(),anyInt())).thenReturn(rows);
        when(adapter.dataRowsAfter(eq("T001"),eq(Set.of("A")),eq("2"),anyInt())).thenReturn(List.of());
        var jdbc=mock(NamedParameterJdbcTemplate.class);var orders=mock(WorkOrderProvider.class);
        var reader=new BusinessReadService(queries,jdbc,orders,1000,120,new BusinessQueryProperties(),service);
        var query=new BusinessQuery(BusinessQuery.Domain.REPORT,BusinessQuery.View.ELIGIBILITY,List.of("sales"),"A",
                List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter("recordId","EQ",List.of("1"))))),null,false,1,1,null);
        var result=reader.query(user,query);assertEquals(1,result.total());assertEquals("1",result.rows().get(0).get("recordId"));
        var evidence=(DispatchEligibility)result.rows().get(0).get("eligibility");assertFalse(evidence.eligible());assertTrue(evidence.reason().contains("已派单"));
        verifyNoInteractions(jdbc,orders,rules);verify(adapter,never()).rowsByIds(anyString(),anyCollection());
    }
}
