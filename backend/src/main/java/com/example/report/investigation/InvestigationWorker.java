package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.config.ResourceQuotaService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

/** 调查专用工作线程和租约续期；页面断开不停止任务，进程中断不自动重放模型，迟到结果不能覆盖终态。 */
@Service
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationWorker {
    private static final Logger log=LoggerFactory.getLogger(InvestigationWorker.class);
    private final InvestigationRepository repository;
    private final InvestigationAccessPolicy access;
    private final InvestigationFacts facts;
    private final InvestigationAgent agent;
    private final ResourceQuotaService quotas;
    private final InvestigationProperties props;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService heartbeat=Executors.newScheduledThreadPool(1);
    private final Map<String,Future<?>> running=new ConcurrentHashMap<>();
    private volatile boolean closed;
    public InvestigationWorker(InvestigationRepository repository,InvestigationAccessPolicy access,InvestigationFacts facts,InvestigationAgent agent,ResourceQuotaService quotas,InvestigationProperties props,PlatformTransactionManager manager,JdbcTemplate jdbc) {
        this.repository=repository;this.access=access;this.facts=facts;this.agent=agent;this.quotas=quotas;this.props=props;this.jdbc=jdbc;
        props.validate();tx=new TransactionTemplate(manager);tx.setTimeout(10);
        workers=new ThreadPoolExecutor(props.getWorkerCount(),props.getWorkerCount(),0,TimeUnit.SECONDS,new SynchronousQueue<>());
    }
    /** 小批轮询只认领可运行任务；线程槽竞争失败明确中断，不重复入队产生额外模型费用。 */
    @Scheduled(fixedDelayString="${agent.investigation.poll-ms:500}")
    public void poll() {
        if(closed) return;
        try {
            repository.recover(access.tenantId());
            for(int i=0;i<props.getWorkerCount() && workers.getActiveCount()<props.getWorkerCount();i++) {
                var run=repository.claim(access.tenantId());if(run==null) break;String id=run.get("id").toString();
                var future=new FutureTask<Void>(() -> {execute(run);return null;});running.put(id,future);
                try {workers.execute(future);} catch(RejectedExecutionException e) {running.remove(id);fail(run,"INTERRUPTED","LEASE_LOST","调查工作槽已变化，请重新分析",null);}
            }
        } catch(Exception e) {log.warn("调查队列暂不可用 type={}",e.getClass().getSimpleName());}
    }
    /** 外部调用均在事务外；本地快照和终态来源检查用短事务固定当前事实。 */
    @SuppressWarnings("unchecked")
    public void execute(Map<String,Object> run) {
        String id=run.get("id").toString(),token=run.get("claim_token").toString();
        var budget=new InvestigationBudget(props);ScheduledFuture<?> renewal=null;
        try {
            // 排队期间重启或跨实例配置变化时，不能用新模型/预算执行却保存旧配置证据。
            var actual=JsonUtil.toMap(JsonUtil.toJson(agent.configuration()));
            if(!InvestigationJson.canonical(InvestigationRepository.json(run,"config_json")).equals(InvestigationJson.canonical(actual)))
                throw new InvestigationFailure("CONFIG_CHANGED","调查执行配置已变化，请重新分析");
            renewal=heartbeat.scheduleAtFixedRate(() -> {try {if(!repository.renew(id,token)) stop(id);} catch(Exception e) {stop(id);}},props.getHeartbeatSeconds(),props.getHeartbeatSeconds(),TimeUnit.SECONDS);
            var actor=access.current(run.get("tenant_id").toString(),run.get("actor_id").toString());
            var request=InvestigationRepository.json(run,"request_json");var selected=(List<String>)request.get("selectedItemIds");
            try(var permit=quotas.acquire(actor,"investigation",List.of())) {
                var snapshot=tx.execute(s -> {
                    facts.select(actor,run.get("plan_id").toString(),selected);
                    var source=facts.snapshot(actor,run.get("plan_id").toString(),selected,true);
                    if(((Number)source.get("executionVersion")).longValue()!=((Number)run.get("expected_execution_version")).longValue()) throw new InvestigationFailure("SOURCE_CHANGED","清单执行轮次已变化，请重新分析");
                    if(!Set.of("EXECUTED","REVIEW_REQUIRED").contains(source.get("status"))) throw new InvestigationFailure("SOURCE_CHANGED","清单已进入其他执行状态");
                    repository.snapshot(id,token,source);return source;
                });
                Runnable guard=() -> {ResourceQuotaService.check(permit);budget.remaining(props.getRunTimeoutSeconds());repository.guard(id,token);access.require(actor,run.get("plan_id").toString());};
                Runnable sourceGuard=() -> {
                    var now=facts.snapshot(actor,run.get("plan_id").toString(),selected,false);
                    if(!snapshot.get("fingerprint").equals(now.get("fingerprint"))) throw new InvestigationFailure("SOURCE_CHANGED","调查来源已变化");
                };
                var session=new InvestigationSession(id,token,actor,snapshot,budget,guard,sourceGuard);
                var report=agent.investigate(session,request.get("question").toString());
                // 按清单→条目→调查运行的锁顺序提交，避免与快照和清理形成反向锁序。
                tx.executeWithoutResult(s -> {
                    var now=facts.snapshot(actor,run.get("plan_id").toString(),selected,true);
                    if(!snapshot.get("fingerprint").equals(now.get("fingerprint"))) session.partialReason="SOURCE_CHANGED";
                    String reason=Objects.toString(session.partialReason,"NORMAL");
                    repository.finish(id,token,session.partialReason==null?"COMPLETED":"PARTIAL",reason,session.partialReason==null?"调查已完成":"调查部分完成，请查看证据和限制",report,budget.summary(),() -> {guard.run();});
                });
            }
        } catch(Exception error) {
            String reason=error instanceof InvestigationFailure f?f.reason():error instanceof ApiException a && Set.of(401,403,404).contains(a.getCode())?"ACCESS_REVOKED":"TOOL_UNAVAILABLE";
            fail(run,"FAILED",reason,"调查未完成："+reason,budget.summary());
        } finally {if(renewal!=null) renewal.cancel(false);running.remove(id);}
    }
    private void fail(Map<String,Object> run,String status,String reason,String message,Object usage) {
        try {repository.finish(run.get("id").toString(),run.get("claim_token").toString(),status,reason,message,null,usage,() -> {});}
        catch(Exception stale) {log.info("调查已失去回写权 run={} reason={}",run.get("id"),reason);}
    }
    public void stop(String id) {var future=running.get(id);if(future!=null) future.cancel(true);}
    @Scheduled(fixedDelayString="${agent.investigation.clean-ms:60000}")
    public void clean() {if(!closed) try {repository.clean(access.tenantId());} catch(Exception e) {log.warn("调查清理暂未完成 type={}",e.getClass().getSimpleName());}}
    @PreDestroy public void close() {closed=true;running.values().forEach(f -> f.cancel(true));workers.shutdownNow();heartbeat.shutdownNow();}
}
