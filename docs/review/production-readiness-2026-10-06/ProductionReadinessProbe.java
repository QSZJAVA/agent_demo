import com.example.business.*;
import com.example.report.catalog.*;
import com.example.report.catalog.query.*;
import com.example.report.common.*;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.*;
import com.example.report.security.IdentityStore;
import com.example.report.operations.SensitiveData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.Answers;
import java.time.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 生产评审的隔离内存探针；只调用现有业务方法，JDBC与来源写入均为测试替身，不建库、不调用模型或ERP。 */
public class ProductionReadinessProbe {
    static final CurrentUser USER=new CurrentUser("T001","reviewer","评审",Set.of("A"),Set.of("report:sales"),false);
    static CatalogEntry catalog(ReportQueryAdapter adapter) {
        return new CatalogEntry("T001","rpt-sales-order","sales","销售报表","sales",null,"STANDARD","{}",true,"PUBLISHED",1,3L,"report:sales",10,null,null,null,"review",LocalDateTime.now(),List.of(),adapter,null);
    }
    /** 证明已确认字段与当前来源字段的差异不会进入现有派单复核谓词；不能将测试成功解释为真实业务写入成功。 */
    static Map<String,Object> changedFields() throws Exception {
        var json=JsonUtil.MAPPER;
        var frozen=new Candidate("rpt-sales-order","销售报表","1","SO-REVIEW","A","测试记录",new BigDecimal("50000"),LocalDate.of(2026,10,6),7L,"金额规则",1,"amount > 20",3L,null,List.of(new FieldFact("amount","decimal","50000")));
        String hash=Digests.sha256(json.writeValueAsString(Arrays.asList(USER.tenantId(),USER.userId(),frozen.reportId(),frozen,true)));
        Map<String,Object> item=new HashMap<>();
        item.put("status","UNKNOWN");item.put("report_id",frozen.reportId());item.put("record_id","1");item.put("company_code","A");
        item.put("fields_json",json.writeValueAsString(frozen.fields()));item.put("catalog_version",3L);item.put("rule_id",7L);item.put("rule_version",1);item.put("preview_source","semantic");
        JdbcTemplate jdbc=mock(JdbcTemplate.class,call->{
            String method=call.getMethod().getName();
            if(call.getArguments().length==0 || !(call.getArgument(0) instanceof String sql))return Answers.RETURNS_DEFAULTS.answer(call);
            if(method.equals("queryForList"))return sql.startsWith("SELECT i.plan_id")?List.of(Map.of("plan_id","review-plan")):List.of();
            if(method.equals("queryForMap")) {
                if(sql.contains("FROM business_dispatch_request"))return Map.of("payload_hash",hash,"operator_id",USER.userId(),"status","PROCESSING");
                if(sql.startsWith("SELECT * FROM dispatch_plan"))return Map.of("status","EXECUTING","confirmed_at",LocalDateTime.now(),"confirmed_by",USER.userId(),"execution_version",1L);
                if(sql.startsWith("SELECT i.*,v.source"))return item;
                throw new AssertionError("Unexpected query: "+sql);
            }
            if(method.equals("update"))return 1;
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
        var adapter=mock(ReportQueryAdapter.class,withSettings().extraInterfaces(DispatchStatusWriter.class));
        var fresh=new FactRow("1","SO-REVIEW","A","测试记录",new BigDecimal("128000"),LocalDate.of(2026,10,6),Map.of("amount",new BigDecimal("128000")));
        when(((DispatchStatusWriter)adapter).markDispatchedGuarded(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->call.<java.util.function.Predicate<FactRow>>getArgument(4).test(fresh));
        var queries=mock(BusinessQueries.class);when(queries.require(USER,"rpt-sales-order",true)).thenReturn(catalog(adapter));
        var rules=mock(DispatchRuleMapper.class);var rule=new DispatchRule();rule.setId(7L);rule.setVersion(1);rule.setCompanyCode("A");rule.setExpression("amount > 20");
        when(rules.publishedReportForUpdate("T001","rpt-sales-order")).thenReturn(List.of(rule));
        var identities=mock(IdentityStore.class);when(identities.resolve(USER.tenantId(),USER.userId())).thenReturn(USER);
        var result=new BusinessDispatch(jdbc,queries,rules,new RuleEngine(),identities,json).submit(USER,"review-request",frozen.reportId(),frozen,true,1L);
        if(!result.success())throw new AssertionError("Expected observed current guard acceptance");
        return Map.of("frozenAmount",50000,"freshAmount",128000,"exampleUserUpperBound",100000,"currentGuardAcceptedChangedAmount",result.success(),"externalWritePerformed",false);
    }
    /** 真实规则引擎对两条合成记录求值；一条空字段失败时仅剩另一条候选，没有失败状态返回。 */
    static Map<String,Object> incompleteCandidates() {
        var adapter=mock(ReportQueryAdapter.class);
        when(adapter.fields()).thenReturn(List.of(new FieldInfo("productName","string","产品名称")));
        var bad=new HashMap<String,Object>();bad.put("productName",null);
        var rows=List.of(new FactRow("1","SO1","A","云服务",BigDecimal.ONE,LocalDate.now(),Map.of("productName","云服务")),new FactRow("2","SO2","A",null,BigDecimal.ONE,LocalDate.now(),bad));
        when(adapter.pendingRowsAfterWithRule(anyString(),anySet(),isNull(),eq(500),anyString())).thenReturn(rows);
        var cache=mock(RuleCache.class);var rule=new DispatchRule();rule.setId(7L);rule.setName("产品规则");rule.setVersion(1);rule.setExpression("string.contains(productName, '云')");
        when(cache.find("T001","rpt-sales-order","A")).thenReturn(Optional.of(rule));
        var result=new DispatchCandidateService(cache,new RuleEngine()).findCandidates("T001",Set.of("A"),List.of(catalog(adapter)));
        if(result.size()!=1)throw new AssertionError("Expected skipped invalid row");
        return Map.of("sourceRows",2,"evaluationErrorRows",1,"returnedCandidates",result.size(),"failureSignalledToCaller",false);
    }
    /** 只用合成占位值检查当前出站文本处理；不打印值或发送网络请求。 */
    static Map<String,Object> redactionCoverage() {
        String synthetic="sk-review-only-not-a-real-key-000000000000";
        return Map.of("syntheticSecretLikeTextRemains",SensitiveData.modelText("接口密钥："+synthetic).text().contains(synthetic),"modelCalled",false);
    }
    public static void main(String[] args)throws Exception {
        var result=Map.of("scope","In-memory review probes; mocked JDBC and source writes; no database, model or ERP calls","fieldDrift",changedFields(),"partialPreview",incompleteCandidates(),"redaction",redactionCoverage());
        Files.writeString(Path.of("docs/review/production-readiness-2026-10-06/probes.json"),JsonUtil.toJson(result));
        System.out.println(JsonUtil.toJson(result));
    }
}
