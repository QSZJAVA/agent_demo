package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.config.ResourceQuotaService;
import com.example.report.permission.PermissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** 有限分析专用工作线程；页面离开不重放任务，取消撤销令牌，进程失租只标记中断而不自动续推理。 */
@Service
@ConditionalOnProperty(name="security.enabled",havingValue="true")
public class AnalysisWorker {
    private final AnalysisRepository repository;private final AnalysisService access;private final AnalysisEngine engine;private final PermissionService permissions;private final ResourceQuotaService quotas;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Future<?>> running=new ConcurrentHashMap<>();private volatile boolean closed;
    public AnalysisWorker(AnalysisRepository repository,AnalysisService access,AnalysisEngine engine,PermissionService permissions,ResourceQuotaService quotas){this.repository=repository;this.access=access;this.engine=engine;this.permissions=permissions;this.quotas=quotas;}
    /** 只认领当前部署租户的任务；工作线程和数据库租约都有界，外部模型调用在事务外。 */
    @Scheduled(fixedDelayString="${agent.analysis.poll-ms:500}",initialDelayString="${agent.analysis.initial-ms:1000}")
    public void poll(){if(closed)return;try{repository.recover(permissions.tenantId());for(int i=0;i<2&&workers.getActiveCount()<2;i++){
        var row=repository.claim(permissions.tenantId());if(row==null)break;String id=row.get("id").toString();var future=new FutureTask<Void>(()->{execute(row);return null;});running.put(id,future);
        try{workers.execute(future);}catch(RejectedExecutionException unavailable){running.remove(id);repository.finish(id,row.get("claim_token").toString(),"FAILED","WORKER_UNAVAILABLE","工作线程暂不可用，请重新发起",null,List.of());}
    }}catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("有限分析队列暂不可用 type={}",failure.getClass().getSimpleName());}}
    /** 每次真实调用和最终发布均重新核验配置、权限、资料与租约；失败不转成另一种业务动作。 */
    public void execute(Map<String,Object> row){String id=row.get("id").toString(),token=row.get("claim_token").toString();ScheduledFuture<?> renewal=null;AnalysisEngine.Session session=null;
        try {
            if(!row.get("config_hash").equals(engine.configurationHash()))throw new ApiException(409,"模型或分析契约配置已变化，请重新提交");
            var user=permissions.resolve(row.get("user_id").toString());if(!user.tenantId().equals(row.get("tenant_id"))||!user.permissionVersion().equals(row.get("permission_version")))throw ApiException.forbidden("排队期间业务权限已变化");
            var source=access.require(user,row);var request=AnalysisContract.decode(row.get("request_json").toString(),AnalysisContract.Request.class);
            renewal=heartbeat.scheduleAtFixedRate(()->{try{if(!repository.renew(id,token))stop(id);}catch(Exception failure){stop(id);}},10,10,TimeUnit.SECONDS);
            try(var permit=quotas.acquire(user,"analysis",List.of(request.reportId()))) {
                Runnable guard=()->{ResourceQuotaService.check(permit);repository.guard(id,token);access.require(user,row);};
                session=new AnalysisEngine.Session(id,token,user,request,source,guard);var outcome=engine.run(session);guard.run();engine.requireSources(user,session.steps,true);session.remaining();
                repository.finish(id,token,outcome.status(),outcome.reason(),AnalysisEvidence.safeText(outcome.message()),outcome.answer(),session.usage);
            }
        } catch(Exception failure){String reason=failure instanceof ApiException api?"HTTP_"+api.getCode():"INTERNAL_ERROR";
            String message=failure instanceof ApiException api?AnalysisEvidence.safeText(api.getMessage()):"分析暂未完成，请查看已完成步骤后重新提交";
            if(!(failure instanceof ApiException))org.slf4j.LoggerFactory.getLogger(getClass()).warn("有限分析执行失败 run={} type={}",id,failure.getClass().getSimpleName());
            // 意外错误也要结束已开始的步骤；失败证据只回写给仍持有租约的原任务，读取仍重新校验权限。
            if(session!=null){for(var step:session.steps)if("RUNNING".equals(step.get("status"))){step.put("status","FAILED");step.put("message",message);}
                try{repository.progress(id,token,"FINISHED",session.plan,session.steps,session.usage);}catch(Exception denied){org.slf4j.LoggerFactory.getLogger(getClass()).info("有限分析失败进度未回写 run={}",id);}}
            try{repository.finish(id,token,session!=null&&session.steps.stream().anyMatch(s->"COMPLETED".equals(s.get("status")))?"PARTIAL":"FAILED",reason,message,null,session==null?List.of():session.usage);}catch(Exception stale){org.slf4j.LoggerFactory.getLogger(getClass()).info("有限分析已失去回写权 run={}",id);}
        } finally {if(renewal!=null)renewal.cancel(false);running.remove(id);}
    }
    public void stop(String id){var future=running.get(id);if(future!=null)future.cancel(true);}
    @Scheduled(fixedDelayString="${agent.analysis.cleanup-ms:60000}") public void cleanup(){if(!closed)try{repository.cleanup(permissions.tenantId());}catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("有限分析清理暂不可用 type={}",failure.getClass().getSimpleName());}}
    @PreDestroy public void close(){closed=true;running.values().forEach(f->f.cancel(true));workers.shutdownNow();heartbeat.shutdownNow();}
}
