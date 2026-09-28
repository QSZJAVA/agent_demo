package com.example.report.operations;

import com.example.report.catalog.*;
import com.example.report.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/** Automatically re-evaluates each committed dictionary / policy / model configuration fingerprint. */
@Service
@Slf4j
public class ResolverEvaluationMonitor {
    private final ReportCatalog catalog;
    private final OperationsPolicy policies;
    private final ResolverEvaluation evaluation;
    private final JdbcTemplate jdbc;
    @Value("${spring.ai.openai.chat.options.model:mock}") private String model;
    public ResolverEvaluationMonitor(ReportCatalog catalog,OperationsPolicy policies,ResolverEvaluation evaluation,JdbcTemplate jdbc) {
        this.catalog=catalog;this.policies=policies;this.evaluation=evaluation;this.jdbc=jdbc;
    }
    public ResolverEvaluation.Evaluation run(String tenant) {
        var entries=catalog.all().stream().filter(e->tenant.equals(e.tenantId())&&e.published()&&e.usable()&&e.effectiveAt(java.time.LocalDateTime.now())).toList();
        var policy=policies.get(tenant,"resolver");
        var corpus=policies.get(tenant,"evaluation");
        var samples=Arrays.asList(JsonUtil.MAPPER.convertValue(corpus.payload().get("samples"),ResolverEvaluation.Sample[].class));
        String fingerprint=Digests.sha256(JsonUtil.toJson(entries.stream().map(e->Map.of("id",e.reportId(),"version",e.catalogVersion(),"aliases",e.aliases())).toList())
                +JsonUtil.toJson(policy)+JsonUtil.toJson(corpus)+model);
        var prior=jdbc.queryForList("SELECT result_json FROM resolver_evaluation_run WHERE tenant_id=? AND fingerprint=?",String.class,tenant,fingerprint);
        if(!prior.isEmpty()) return JsonUtil.fromJson(prior.get(0),ResolverEvaluation.Evaluation.class);
        var result=evaluation.evaluate(entries,((Number)policy.payload().get("fuzzyThreshold")).doubleValue(),((Number)policy.payload().get("ambiguityMargin")).doubleValue(),samples);
        jdbc.update("INSERT IGNORE INTO resolver_evaluation_run(tenant_id,fingerprint,total_count,passed_count,result_json,created_at) VALUES (?,?,?,?,?,NOW(3))",tenant,fingerprint,result.total(),result.passed(),JsonUtil.toJson(result));
        if(result.passed()!=result.total()) log.warn("解析回归失败 tenant={} passed={}/{}",tenant,result.passed(),result.total());
        return result;
    }
    @Scheduled(fixedDelayString="${agent.evaluation-sweep-ms:60000}")
    public void sweep() {
        try { for(String tenant:catalog.all().stream().map(CatalogEntry::tenantId).distinct().toList()) run(tenant); }
        catch(RuntimeException failure) { log.warn("解析自动回归未完成，下轮重试",failure); }
    }
}
