package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.config.ResourceQuotaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    @ParameterizedTest
    @MethodSource("equivalentNumbers")
    void equalNumericRepresentationsStillRevalidateIdentity(Object actual,String stored) {
        assertConfiguration(Map.of("model","same","value",actual),"{\"value\":"+stored+",\"model\":\"same\"}",true);
    }
    static java.util.stream.Stream<Arguments> equivalentNumbers() {
        return java.util.stream.Stream.of(
                Arguments.of(1,"1.000"),Arguments.of(1L,"1.0"),Arguments.of(new java.math.BigDecimal("1.000"),"1"),
                Arguments.of(new java.math.BigDecimal("90071992547409.9300"),"90071992547409.93"),
                Arguments.of(new java.math.BigDecimal("0.0000000000000000000001"),"0.000000000000000000000100"),
                Arguments.of(Map.of("price",new java.math.BigDecimal("1.000"),"calls",8),"{\"calls\":8.0,\"price\":1.0}"));
    }
    @ParameterizedTest
    @MethodSource("changedValues")
    void realValueAndTypeChangesStopBeforeIdentityOrModelExecution(Object actual,String stored) {
        assertConfiguration(Map.of("model","same","value",actual),"{\"value\":"+stored+",\"model\":\"same\"}",false);
    }
    static java.util.stream.Stream<Arguments> changedValues() {
        return java.util.stream.Stream.of(
                Arguments.of(new java.math.BigDecimal("90071992547409.93"),"90071992547409.94"),
                Arguments.of(new java.math.BigDecimal("0.0000000000000000000001"),"0.0000000000000000000002"),
                Arguments.of(true,"1"),Arguments.of("1","1"),Arguments.of(Map.of("calls",9),"{\"calls\":8.000}"));
    }
    private void assertConfiguration(Map<String,Object> actual,String stored,boolean equivalent) {
        var repo=mock(InvestigationRepository.class);var access=mock(InvestigationAccessPolicy.class);var agent=mock(InvestigationAgent.class);var quotas=mock(ResourceQuotaService.class);
        when(agent.configuration()).thenReturn(actual);
        when(access.current("tenant","actor")).thenThrow(new ApiException(403,"已撤权"));
        var worker=worker(repo,access,agent,quotas);
        try {worker.execute(Map.of("id","run","claim_token","token","tenant_id","tenant","actor_id","actor","config_json",stored));}
        finally {worker.close();}
        if(equivalent)verify(access).current("tenant","actor");else verifyNoInteractions(access);
        verify(repo).finish(eq("run"),eq("token"),eq("FAILED"),eq(equivalent?"ACCESS_REVOKED":"CONFIG_CHANGED"),anyString(),isNull(),any(),any());
        verifyNoInteractions(quotas);verify(agent,never()).investigate(any(),anyString());
    }
    private InvestigationWorker worker(InvestigationRepository repo,InvestigationAccessPolicy access,InvestigationAgent agent,ResourceQuotaService quotas) {
        return new InvestigationWorker(repo,access,mock(InvestigationFacts.class),agent,quotas,new InvestigationProperties(),mock(PlatformTransactionManager.class),mock(JdbcTemplate.class));
    }
}
