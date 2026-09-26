package com.example.report.catalog.query;

import com.example.report.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标准报表查询配置：标识符白名单、未知字段、字段名冲突都在保存时拦下
 */
class StandardQueryConfigTest {
    @org.junit.jupiter.api.Test
    void missingTenantColumnIsRejected() {
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"tenantColumn\":\"tenant_id\",", "")));
    }

    /** 与 V2 迁移中销售报表的配置一致 */
    static final String SALES = """
            {"table":"report_sales","idColumn":"id","companyColumn":"company_code","tenantColumn":"tenant_id","docNoColumn":"order_no","docNoLabel":"订单号",
             "labelColumn":"product_name","amountColumn":"amount","dateColumn":"sale_date","statusColumn":"dispatch_status",
             "pendingValue":0,"dispatchedValue":1,"dispatchedAtColumn":"dispatched_at",
             "fields":[{"name":"companyCode","column":"company_code","type":"string","description":"公司代码"},
                       {"name":"orderNo","column":"order_no","type":"string","description":"订单号"},
                       {"name":"productName","column":"product_name","type":"string","description":"产品名称"},
                       {"name":"amount","column":"amount","type":"decimal","description":"订单金额（元）"},
                       {"name":"saleDate","column":"sale_date","type":"date","description":"销售日期，字符串 yyyy-MM-dd"}],
             "derived":[{"name":"daysSinceSale","kind":"DAYS_SINCE","column":"sale_date","description":"距销售日期的天数（派生）"},
                        {"name":"dispatched","kind":"DISPATCHED","description":"是否已派单（派生，粗筛后恒为 false）"}]}
            """;

    @Test
    void seedConfigParsesAndExposesTheSameFieldsAsTheOldAssembler() {
        StandardQueryConfig config = StandardQueryConfig.parse(SALES);
        StandardReportAdapter adapter = new StandardReportAdapter(config, null);
        assertEquals(List.of("companyCode", "orderNo", "productName", "amount", "saleDate", "daysSinceSale", "dispatched"),
                adapter.fields().stream().map(FieldInfo::name).toList());
        assertEquals(List.of("string", "string", "string", "decimal", "date", "long", "boolean"),
                adapter.fields().stream().map(FieldInfo::type).toList());
        assertEquals("订单号", adapter.docNoLabel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"report_sales; DROP TABLE x", "report sales", "`report_sales`", "report_sales--", "a.b.c",
            "1report", "report'sales"})
    void tableMustBeAPlainIdentifier(String table) {
        ApiException e = assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"report_sales\"",
                "\"" + table.replace("\"", "\\\"") + "\"")));
        assertTrue(e.getMessage().contains("table"), e.getMessage());
    }

    @Test
    void columnsMustBePlainIdentifiers() {
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"idColumn\":\"id\"",
                "\"idColumn\":\"id) OR (1=1\"")));
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"column\":\"order_no\"",
                "\"column\":\"order_no, password\"")));
    }

    @Test
    void schemaQualifiedTableIsAllowed() {
        StandardQueryConfig config = StandardQueryConfig.parse(SALES.replace("\"report_sales\"", "\"erp.report_sales\""));
        assertEquals("erp.report_sales", config.table());
        assertEquals("`erp`.`report_sales`", StandardReportAdapter.quote(config.table()));
    }

    @Test
    void misspelledKeyIsRejectedInsteadOfSilentlyIgnored() {
        ApiException e = assertThrows(ApiException.class, () -> StandardQueryConfig.parse(
                SALES.replace("\"dateColumn\"", "\"dateColum\"")));
        assertTrue(e.getMessage().contains("dateColum"), e.getMessage());
    }

    @Test
    void factNamesMustBeUsableInRuleExpressions() {
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"name\":\"orderNo\"", "\"name\":\"nil\"")));
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"name\":\"orderNo\"", "\"name\":\"order-no\"")));
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"name\":\"orderNo\"", "\"name\":\"amount\"")),
                "字段名重复");
    }

    @Test
    void typesKindsAndStatusValuesAreValidated() {
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"type\":\"decimal\"", "\"type\":\"money\"")));
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"kind\":\"DAYS_SINCE\"", "\"kind\":\"SQL\"")));
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"dispatchedValue\":1", "\"dispatchedValue\":0")));
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace(
                "\"kind\":\"DAYS_SINCE\",\"column\":\"sale_date\"", "\"kind\":\"DAYS_SINCE\"")), "日期派生字段必须指定列");
    }

    @Test
    void requiredColumnsMustBePresent() {
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(SALES.replace("\"companyColumn\":\"company_code\",", "")),
                "没有公司列就无法强制数据范围");
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse(""));
        assertThrows(ApiException.class, () -> StandardQueryConfig.parse("not json"));
    }
}
