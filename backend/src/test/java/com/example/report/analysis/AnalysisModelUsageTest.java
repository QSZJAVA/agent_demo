package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.permission.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 核对实际模型费用和后置权限失败的共同边界；已收到响应用量不能因结果被拒绝发布而丢失。 */
class AnalysisModelUsageTest {
    @SuppressWarnings("unchecked")
    static AnalysisEngine engine(AnalysisModel model){var provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(model);return new AnalysisEngine(provider,null,mock(AnalysisRepository.class),null,null,null);}
    static AnalysisEngine.Session session(Runnable guard){return new AnalysisEngine.Session("run","lease",new CurrentUser("T001","reader","用户",Set.of("A"),Set.of("report:sales"),false),new AnalysisContract.Request("A","rpt-sales-order","说明口径",AnalysisContract.Mode.KNOWLEDGE_ONLY,null),KnowledgeRagTest.snapshot(List.of()),guard);}
    static void call(AnalysisEngine engine,AnalysisEngine.Session session){ReflectionTestUtils.invokeMethod(engine,"call",session,"RAG_REWRITE","server instructions",Map.of(),"{}");}
    @Test void receivedUsageSurvivesPermissionOrVersionFailureAfterResponse() {
        var model=mock(AnalysisModel.class);when(model.call(anyString(),any(),anyString(),any())).thenReturn(new AnalysisModel.Reply("{}",new ModelUsage(120,30,15),25,"actual-model"));
        var guards=new AtomicInteger();var session=session(()->{if(guards.incrementAndGet()==3)throw new ApiException(409,"发布版本已变化");});
        assertEquals(409,assertThrows(ApiException.class,()->call(engine(model),session)).getCode());var usage=session.usage.get(0);
        assertEquals("RESPONDED",usage.get("status"));assertEquals("REJECTED",usage.get("validation"));assertEquals(120,usage.get("inputTokens"));assertEquals(30,usage.get("outputTokens"));assertEquals(15,usage.get("cachedInputTokens"));assertEquals(true,usage.get("usageComplete"));assertEquals(25L,usage.get("elapsedMs"));
    }
    @Test void aTransportFailureKeepsUnknownUsageAndStillConsumesOneAttempt() {
        var model=mock(AnalysisModel.class);when(model.call(anyString(),any(),anyString(),any())).thenThrow(new ApiException(503,"模型传输未完成"));var session=session(()->{});
        assertThrows(ApiException.class,()->call(engine(model),session));assertEquals(1,session.usage.size());var usage=session.usage.get(0);
        assertEquals("FAILED",usage.get("status"));assertNull(usage.get("inputTokens"));assertNull(usage.get("outputTokens"));assertNull(usage.get("cachedInputTokens"));assertEquals(false,usage.get("usageComplete"));
    }
    @Test void missingNativeUsageIsNotInventedAfterASuccessfulResponse() {
        var model=mock(AnalysisModel.class);when(model.call(anyString(),any(),anyString(),any())).thenReturn(new AnalysisModel.Reply("{}",new ModelUsage(null,null,null),25,"actual-model"));var session=session(()->{});
        call(engine(model),session);assertEquals("RESPONDED",session.usage.get(0).get("status"));assertEquals(false,session.usage.get(0).get("usageComplete"));assertNull(session.usage.get(0).get("inputTokens"));
    }
}
