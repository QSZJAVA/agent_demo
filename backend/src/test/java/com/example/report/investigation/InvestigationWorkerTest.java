package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.config.ResourceQuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 排队任务配置隔离回归；跨重启或跨实例变化先失败，不能调用新模型却保留旧配置依据。 */
class InvestigationWorkerTest {
    @Test void changedConfigurationStopsBeforeIdentityQuotaOrAgentExecution() {
        var repo=mock(InvestigationRepository.class);var access=mock(InvestigationAccessPolicy.class);var agent=mock(InvestigationAgent.class);var quotas=mock(ResourceQuotaService.class);
        when(agent.configuration()).thenReturn(Map.of("model","new-model","budgets",Map.of("maxModelCalls",8)));
        var worker=worker(repo,access,agent,quotas);try {
            worker.execute(Map.of("id","run","claim_token","token","config_json",JsonUtil.toJson(Map.of("model","old-model","budgets",Map.of("maxModelCalls",8)))));
        } finally {worker.close();}
        verify(repo).finish(eq("run"),eq("token"),eq("FAILED"),eq("CONFIG_CHANGED"),anyString(),isNull(),any(),any());
        verifyNoInteractions(access,quotas);verify(agent,never()).investigate(any(),anyString());
    }
    @Test void reorderedAndJsonRoundTrippedConfigurationStillRevalidatesIdentity() {
        var repo=mock(InvestigationRepository.class);var access=mock(InvestigationAccessPolicy.class);var agent=mock(InvestigationAgent.class);var quotas=mock(ResourceQuotaService.class);
        var config=new LinkedHashMap<String,Object>();config.put("model","same");config.put("price",new java.math.BigDecimal("1.000"));
        when(agent.configuration()).thenReturn(config);when(access.current("tenant","actor")).thenThrow(new ApiException(403,"已撤权"));
        var worker=worker(repo,access,agent,quotas);try {worker.execute(Map.of("id","run","claim_token","token","tenant_id","tenant","actor_id","actor","config_json","{\"price\":1.0,\"model\":\"same\"}"));} finally {worker.close();}
        verify(access).current("tenant","actor");verify(repo).finish(eq("run"),eq("token"),eq("FAILED"),eq("ACCESS_REVOKED"),anyString(),isNull(),any(),any());
    }
    private InvestigationWorker worker(InvestigationRepository repo,InvestigationAccessPolicy access,InvestigationAgent agent,ResourceQuotaService quotas) {
        return new InvestigationWorker(repo,access,mock(InvestigationFacts.class),agent,quotas,new InvestigationProperties(),mock(PlatformTransactionManager.class),mock(JdbcTemplate.class));
    }
}
