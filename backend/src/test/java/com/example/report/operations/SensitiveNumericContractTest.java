package com.example.report.operations;

import com.example.report.agent.PlanPayload;
import com.example.report.agent.PreviewPayload;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.JsonUtil;
import com.example.report.common.Result;
import com.example.report.config.AgentProperties;
import com.example.report.conversation.ConversationService;
import com.example.report.dispatch.DispatchResultPayload;
import com.example.report.entity.SalesReport;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import com.example.report.report.ReportPage;
import com.example.report.report.ReportService;
import com.example.report.rule.Candidate;
import com.example.report.rule.FieldFact;
import com.example.report.web.ReportController;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 精确数值与脱敏边界的交叉回归；覆盖真实MVC响应通知、SSE类型投影、任务及卡片回读和模型出站。
 * 数值仅在当前金额或声明标量契约下保留；敏感字段优先，普通数字文本仍须脱敏。
 */
class SensitiveNumericContractTest {
    private static final String PHONE="13800138000";
    private static final String MONEY=PHONE+".00";
    private static final String LONG_VALUE="110101199001011234";

    @ParameterizedTest
    @ValueSource(strings={"13800138000.00","-19900000000.01","18812345678.90","90071992547409.93","0.00"})
    void reportHttpAdvicePreservesMoneyAndStillMasksContactText(String amount) throws Exception {
        var user=new CurrentUser("T001","reader","读者",Set.of("A"),Set.of("report:sales"),false);
        var permissions=mock(PermissionService.class);when(permissions.resolve("reader")).thenReturn(user);
        var reports=mock(ReportService.class);
        var row=new SalesReport();row.setId(1L);row.setAmount(new BigDecimal(amount));row.setProductName("联系"+PHONE);
        when(reports.pageSales(user,1,50)).thenReturn(new ReportPage<>(List.of(row),1,1,50));
        var mvc=MockMvcBuilders.standaloneSetup(new ReportController(reports,permissions,mock(ReportCatalogService.class)))
                .setControllerAdvice(new SensitiveResponseAdvice()).build();

        var response=mvc.perform(get("/api/report/sales/page").header(PermissionService.USER_HEADER,"reader"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var actual=JsonUtil.MAPPER.readTree(response).at("/data/records/0");
        assertExact(actual.path("amount"),amount);
        assertFalse(actual.path("productName").asText().contains(PHONE));
    }

    @Test
    void sseTypedProjectionKeepsAnnotatedAmountsAndDeclaredNumericFacts() {
        var original=preview();
        var safe=assertInstanceOf(PreviewPayload.class,SensitiveData.typed(original));
        assertEquals(new BigDecimal(MONEY),safe.totalAmount());
        assertEquals(new BigDecimal(MONEY),safe.byReport().get(0).amount());
        assertEquals(new BigDecimal(MONEY),safe.records().get(0).amount());
        assertEquals(MONEY,safe.records().get(0).fields().get(0).value());
        assertEquals(LONG_VALUE,safe.records().get(0).fields().get(1).value());
        assertFalse(safe.records().get(0).label().contains(PHONE));
        assertFalse(safe.records().get(0).fields().get(2).value().contains(PHONE));
        assertEquals(MONEY,original.records().get(0).fields().get(0).value(),"投影不得修改已确认的来源事实");
    }

    @Test
    void persistedTaskAndCappedCardHistoryKeepTheSameExactString() {
        var candidate=candidate();
        var result=new DispatchResultPayload("plan","preview",1,1,0,0,List.of(candidate),List.of(),false);
        // 与任务工作线程相同，先脱敏再序列化；随后按无类型任务结果和HTTP响应再次投影。
        var storedTask=JsonUtil.toMap(JsonUtil.toJson(SensitiveData.value(result)));
        var taskResponse=SensitiveData.value(Result.ok(storedTask));
        assertExact(taskResponse.at("/data/success/0/amount"),MONEY);
        assertExact(taskResponse.at("/data/success/0/fields/0/value"),MONEY);
        assertFalse(taskResponse.at("/data/success/0/label").asText().contains(PHONE));

        var conversations=new ConversationService(null,null,new AgentProperties(),null,null);
        var plan=new PlanPayload("plan","preview","PENDING",1,List.of(),List.of(candidate),LocalDateTime.of(2026,10,11,13,0));
        for(Object payload:List.of(preview(),plan)) {
            Object capped=ReflectionTestUtils.invokeMethod(conversations,"capPayload",payload);
            var stored=JsonUtil.toMap(JsonUtil.toJson(capped));
            var sanitized=SensitiveData.value(stored);
            var restored=JsonUtil.toMap(JsonUtil.toJson(sanitized));
            var historyResponse=SensitiveData.value(Result.ok(restored));
            assertExact(historyResponse.at("/data/records/0/amount"),MONEY);
            assertExact(historyResponse.at("/data/records/0/fields/0/value"),MONEY);
        }
    }

    @Test
    void serializedFieldFactColumnsKeepNumbersAfterEntityAndJdbcProjection() throws Exception {
        String fields=JsonUtil.toJson(candidate().fields());
        var item=new DispatchPlanItem();item.setAmount(new BigDecimal(MONEY));item.setFieldsJson(fields);
        for(var clean:List.of(SensitiveData.value(item),SensitiveData.forModel(item),
                SensitiveData.value(Map.of("fields_json",fields)))) {
            String json=clean.has("fieldsJson")?clean.path("fieldsJson").asText():clean.path("fields_json").asText();
            var restored=JsonUtil.MAPPER.readTree(json);
            assertExact(restored.at("/0/value"),MONEY);
            assertExact(restored.at("/1/value"),LONG_VALUE);
            assertFalse(restored.at("/2/value").asText().contains(PHONE));
        }
        assertEquals(fields,item.getFieldsJson());
    }

    @Test
    void modelAndUntypedQueryResultsUseDeclaredColumnsAndCurrencyTotals() {
        var source=Map.of("columns",List.of(
                        Map.of("name","taxBase","type","decimal","description","计税基数"),
                        Map.of("name","volume","type","long","description","业务累计量"),
                        Map.of("name","contact","type","string","description","联系文本")),
                "rows",List.of(Map.of("taxBase",MONEY,"volume",LONG_VALUE,"contact",PHONE,"amount",MONEY)),
                "summary",Map.of("amountsByCurrency",Map.of("CNY",MONEY,"USD","-19900000000.01")),
                "fields",List.of(new FieldFact("taxBase","decimal",MONEY),new FieldFact("volume","long",LONG_VALUE)),
                "label","请联系"+PHONE);
        var restored=JsonUtil.toMap(JsonUtil.toJson(source));
        for(var clean:List.of(SensitiveData.value(restored),SensitiveData.forModel(restored))) {
            assertExact(clean.at("/rows/0/taxBase"),MONEY);
            assertExact(clean.at("/rows/0/volume"),LONG_VALUE);
            assertExact(clean.at("/rows/0/amount"),MONEY);
            assertExact(clean.at("/summary/amountsByCurrency/CNY"),MONEY);
            assertExact(clean.at("/summary/amountsByCurrency/USD"),"-19900000000.01");
            assertExact(clean.at("/fields/0/value"),MONEY);
            assertExact(clean.at("/fields/1/value"),LONG_VALUE);
            assertFalse(clean.at("/rows/0/contact").asText().contains(PHONE));
            assertFalse(clean.path("label").asText().contains(PHONE));
        }
        assertExact(SensitiveData.forModel(candidate()).path("amount"),MONEY);
    }

    @Test
    void explicitSensitiveFieldsCannotBorrowNumericContracts() {
        var source=Map.of("phone",PHONE,"password",Map.of("amount",MONEY),
                "fields",List.of(new FieldFact("bank_account","long",LONG_VALUE),new FieldFact("mobile","decimal",MONEY)),
                "columns",List.of(Map.of("name","phone","type","long","description","敏感号码")),
                "rows",List.of(Map.of("phone",PHONE)),"amountsByCurrency",Map.of("token",MONEY));
        for(var clean:List.of(SensitiveData.value(source),SensitiveData.forModel(source))) {
            assertFalse(clean.path("phone").asText().contains(PHONE));
            assertFalse(clean.path("password").toString().contains(MONEY));
            assertFalse(clean.at("/fields/0/value").asText().contains(LONG_VALUE));
            assertFalse(clean.at("/fields/1/value").asText().contains(PHONE));
            assertFalse(clean.at("/rows/0/phone").asText().contains(PHONE));
            assertFalse(clean.at("/amountsByCurrency/token").asText().contains(PHONE));
        }
        var privateNumber=new FieldFact("bank_account","long",LONG_VALUE);
        var safe=assertInstanceOf(FieldFact.class,SensitiveData.typed(privateNumber));
        assertNull(safe.value(),"敏感数值字段以空值投影，不用文本占位破坏声明类型");
        assertEquals(LONG_VALUE,privateNumber.value());
    }

    @Test
    void numericLookingFreeTextAndInvalidTypedValuesStillReceiveRedaction() {
        var source=Map.of("contact",PHONE,"amount","联系"+PHONE,"totalAmount",Map.of("note",PHONE),
                "fields",List.of(new FieldFact("amount","string",PHONE),
                        Map.of("name","units","type","integer","value",PHONE),
                        Map.of("name","units","type","long","value",MONEY+"1")),
                "columns",List.of(Map.of("name","amount","type","string","description","文本字段")),
                "rows",List.of(Map.of("amount",PHONE)),"amountsByCurrency",Map.of("CNY","请联系"+PHONE));
        for(var clean:List.of(SensitiveData.value(source),SensitiveData.forModel(source))) {
            assertFalse(clean.toString().contains(PHONE),"只保留通过当前数值类型校验的值，不能放过所有数字字符串或整棵对象");
        }
    }

    @Test
    void scalarDeclarationsDoNotAuthorizeArraysOrMalformedFieldFactJson() {
        var source=Map.of("amount",List.of(PHONE),"amountsByCurrency",Map.of("CNY",List.of(PHONE)),
                "fields",List.of(Map.of("name","units","type","long","value",List.of(PHONE))),
                "fieldsJson","[{\"name\":\"amount\",\"type\":\"decimal\",\"value\":\"联系"+PHONE+"\"}]");
        for(var clean:List.of(SensitiveData.value(source),SensitiveData.forModel(source)))
            assertFalse(clean.toString().contains(PHONE));
    }

    private static Candidate candidate() {
        return new Candidate("rpt-sales-order","销售报表","1","SO1","A","联系"+PHONE,new BigDecimal(MONEY),
                LocalDate.of(2026,10,11),1L,"金额规则",1,"按金额检查",1L,null,
                List.of(new FieldFact("amount","decimal",MONEY),new FieldFact("volume","long",LONG_VALUE),
                        new FieldFact("contact","string",PHONE)));
    }

    private static PreviewPayload preview() {
        return new PreviewPayload("preview","ACTIVE",1,new BigDecimal(MONEY),
                List.of(new PreviewPayload.ReportCount("rpt-sales-order","销售报表",1,new BigDecimal(MONEY))),
                List.of(candidate()),Map.of(),null,LocalDateTime.of(2026,10,11,12,0),LocalDateTime.of(2026,10,11,13,0));
    }

    private static void assertExact(JsonNode node,String expected) {
        assertTrue(node.isTextual(),()->"数值契约必须仍为字符串："+node);
        assertEquals(expected,node.textValue());
    }
}
