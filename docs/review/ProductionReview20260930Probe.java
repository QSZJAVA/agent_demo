import com.example.report.common.*;
import com.example.report.entity.*;
import com.example.report.operations.*;
import com.example.report.permission.*;
import com.example.report.security.*;
import com.example.report.dispatch.*;
import com.example.report.mapper.DispatchPreviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fix verification; original defect outputs are retained in the review report. No live data is changed. */
public class ProductionReview20260930Probe {
    public static void main(String[] args) throws Exception {
        SalesReport sale = new SalesReport();
        sale.setId(9007199254740993L); sale.setCompanyCode("A"); sale.setOrderNo("SYNTHETIC-ODD-ID");
        String payload = JsonUtil.toJson(SensitiveData.value(Result.ok(List.of(sale))));
        assertTrue(payload.contains("\"id\":\"9007199254740993\""));
        Files.writeString(Path.of("backend/target/review-20260930-large-id.json"),payload);
        System.out.println("B1 report JSON preserves string ID 9007199254740993");

        IdentityStore identities = mock(IdentityStore.class);
        when(identities.authenticate(null)).thenThrow(new ApiException(401,"please login"));
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS","/api/report/sales/page");
        preflight.setServletPath("/api/report/sales/page");
        preflight.addHeader("Origin","https://ui.example.test");
        preflight.addHeader("Access-Control-Request-Method","GET");
        preflight.addHeader("Access-Control-Request-Headers","authorization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        new SessionFilter(identities,new ObjectMapper()).doFilter(preflight,response,(r,s)->reached.set(true));
        assertTrue(reached.get());verifyNoInteractions(identities);
        System.out.println("B2 preflight reaches CORS validation without session authentication");

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PermissionService permissions = new PermissionService();
        ReflectionTestUtils.setField(permissions,"identities",identities);
        when(identities.tenant()).thenReturn("T001");
        when(identities.resolve("T001","disabled-owner")).thenThrow(ApiException.forbidden("user disabled"));
        when(jdbc.queryForList("SELECT user_id,preview_id FROM dispatch_plan WHERE tenant_id=? AND id=?","T001","synthetic-plan"))
                .thenReturn(List.of(Map.of("user_id","disabled-owner","preview_id","synthetic-preview")));
        DispatchPreview preview = new DispatchPreview(); preview.setTenantId("T001");
        DispatchPreviewMapper previewMapper = mock(DispatchPreviewMapper.class);
        when(previewMapper.selectById("synthetic-preview")).thenReturn(preview);
        PreviewService previews = mock(PreviewService.class);
        DispatchService dispatch = mock(DispatchService.class);
        OperationsWorkbench workbench = new OperationsWorkbench(jdbc,permissions,previews,previewMapper,
                mock(PlanService.class),dispatch,mock(OperationsAudit.class),mock(TransactionOperations.class));
        CurrentUser admin = new CurrentUser("T001","admin","Admin",Set.of("A"),Set.of("*"),true);
        workbench.act(admin,"synthetic-plan","reconcile","review by authorized admin");
        verify(dispatch).reconcileForOperator(admin,"disabled-owner","synthetic-plan");
        verify(identities,never()).resolve(anyString(),anyString());
        verify(previews).requireReadable(admin,preview);
        System.out.println("B3 authorized admin can reconcile disabled owner's plan with actual actor preserved");

        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String,String> values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String,Long> counters = new HashMap<>();
        when(values.increment(anyString())).thenAnswer(invocation -> counters.merge(invocation.getArgument(0),1L,Long::sum));
        IdentityStore loginStore = new IdentityStore(mock(JdbcTemplate.class),new ObjectMapper(),redis,"T001","");
        var addresses=new ClientAddressResolver("10.0.0.8");
        for(int i=0;i<41;i++) {
            String name = "synthetic-user-"+i;
            var request=new MockHttpServletRequest();request.setRemoteAddr("10.0.0.8");request.addHeader("X-Forwarded-For","198.51.100."+(i+1));
            assertEquals(401,assertThrows(ApiException.class,()->loginStore.login(name,null,addresses.resolve(request))).getCode());
        }
        System.out.println("B5 41 clients behind trusted proxy retain independent IP rate buckets");
    }
}
