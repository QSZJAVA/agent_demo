package com.example.report.catalog.query;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标准报表适配器生成的 SQL：数据范围（公司、租户）与待派单状态是强制条件，标识符加反引号，值全部参数绑定
 */
class StandardReportAdapterTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StandardReportAdapter adapter =
            new StandardReportAdapter(StandardQueryConfig.parse(StandardQueryConfigTest.SALES), jdbc);

    @Test
    @SuppressWarnings("unchecked")
    void pendingRowsAlwaysFilterByCompanyScopeAndPendingStatus() {
        adapter.pendingRows("T001", Set.of("A"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().startsWith("SELECT `id`, `company_code`, `order_no`, `dispatch_status`"), sql.getValue());
        assertTrue(sql.getValue().contains(" FROM `report_sales` WHERE `company_code` IN (:companies) AND `dispatch_status` = :pending"));
        assertTrue(sql.getValue().endsWith(" ORDER BY `company_code`, `sale_date`, `id`"));
        assertEquals(Set.of("A"), params.getValue().getValue("companies"));
        assertEquals(0, params.getValue().getValue("pending"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyCompanyScopeQueriesNothing() {
        assertTrue(adapter.pendingRows("T001", Set.of()).isEmpty());
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantColumnIsEnforcedAndMissingTenantReturnsNothing() {
        StandardReportAdapter tenantAware = new StandardReportAdapter(StandardQueryConfig.parse(StandardQueryConfigTest.SALES), jdbc);
        tenantAware.pendingRows("T001", Set.of("A"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().contains("AND `tenant_id` = :tenantId"), sql.getValue());
        assertEquals("T001", params.getValue().getValue("tenantId"));
        // 登录态没有租户时宁可查不到，也不能跨租户
        assertTrue(tenantAware.pendingRows(null, Set.of("A")).isEmpty());
        assertFalse(tenantAware.markDispatched(null, "1", LocalDateTime.now()));
    }

    @Test
    void markDispatchedOnlyUpdatesRecordsThatAreStillPending() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertTrue(adapter.markDispatched("T001", "7", LocalDateTime.of(2026, 1, 1, 0, 0)));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertEquals("UPDATE `report_sales` SET `dispatch_status` = :dispatched, `dispatched_at` = :dispatchedAt"
                + " WHERE `id` = :id AND `dispatch_status` = :pending AND `tenant_id` = :tenantId", sql.getValue());
        assertEquals("7", params.getValue().getValue("id"));
        assertEquals(1, params.getValue().getValue("dispatched"));
    }

    @Test
    void alreadyDispatchedRecordIsNotUpdatedTwice() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertFalse(adapter.markDispatched("T001", "7", LocalDateTime.now()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowsAreMappedToFactsWithDerivedFields() throws Exception {
        LocalDate saleDate = LocalDate.now().minusDays(10);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("7");
        when(rs.getString("company_code")).thenReturn("A");
        when(rs.getString("order_no")).thenReturn("SO2026007");
        when(rs.getString("product_name")).thenReturn("云服务");
        when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("96000.00"));
        when(rs.getObject("sale_date", LocalDate.class)).thenReturn(saleDate);
        when(rs.getObject("dispatch_status")).thenReturn(0);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(inv -> {
            RowMapper<FactRow> mapper = inv.getArgument(2);
            return List.of(mapper.mapRow(rs, 0));
        });
        FactRow row = adapter.pendingRows("T001", Set.of("A")).get(0);
        assertEquals("7", row.recordId());
        assertEquals("SO2026007", row.docNo());
        assertEquals("云服务", row.label());
        assertEquals(saleDate, row.date());
        assertEquals(new BigDecimal("96000.00"), row.facts().get("amount"));
        assertEquals(saleDate.toString(), row.facts().get("saleDate"), "日期字段按 yyyy-MM-dd 字符串进入事实模型，与旧装配器一致");
        assertEquals(10L, row.facts().get("daysSinceSale"));
        assertEquals(false, row.facts().get("dispatched"));
        verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void rowsByIdsBindsIdsAsParameters() {
        adapter.rowsByIds("T001", List.of("1", "2 OR 1=1"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().contains("WHERE `id` IN (:ids)"));
        assertFalse(sql.getValue().contains("1=1"), "主键值只能作为参数绑定");
        assertEquals(List.of("1", "2 OR 1=1"), params.getValue().getValue("ids"));
    }
}
