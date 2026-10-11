package com.example.report.dispatch;

import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.*;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.operations.*;
import com.example.report.permission.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.slf4j.MDC;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 持久化派单命令的入队、认领、执行及恢复服务。
 * 提交先落库，再由工作线程执行；只认领 QUEUED 任务，RUNNING 中断后置 FAILED 并要求核对，绝不自动重新派单。
 * 租户锁串行化容量检查，认领令牌和清单执行版本阻止过期工作线程回写；按当前业务授权读取任务和有界历史，不从任务负载恢复执行授权。
 */
@Slf4j
@Service
public class DispatchJobService {
    /**
     * 异步任务请求；清单动作与人工记录范围互斥。
     * @param planId 清单动作的目标标识；DIRECT尚无清单时必须为空
     * @param action CONFIRM、RETRY_FAILED、RECONCILE、DIRECT、OP_RETRY_FAILED或OP_RECONCILE
     * @param reportId 仅DIRECT使用的报表标识；清单动作必须为空
     * @param recordIds 仅DIRECT使用的1～50条人工记录范围；清单动作必须为空
     * @param reason 仅运维动作要求的操作原因，普通动作不保存原因
     */
    public record Request(String planId,String action,String reportId,List<String> recordIds,String reason) {}
    /**
     * 持久化任务状态的展示快照；终态由服务端决定。
     * @param id 持久化任务主键，用于后续轮询
     * @param planId 派单清单标识，关联服务端持久化清单
     * @param action 本次业务动作，必须属于协议允许的动作集合
     * @param status QUEUED排队、RUNNING执行、SUCCEEDED完成或FAILED失败
     * @param message 可展示的操作摘要或失败原因，禁止包含凭据
     * @param result 异步业务结果；未完成或已过保留期时为空
     * @param idempotencyKey 首次提交前固定的操作幂等键，相同键不得对应不同负载
     */
    public record Job(String id,String planId,String action,String status,String message,Object result,String idempotencyKey) {}
    /**
     * 历史任务中可继续查看的清单链接；只提供当前有权读取的既有对象，不携带执行授权。
     * @param planId 已持久化清单标识，详情和追溯入口仍须独立校验权限
     * @param conversationId 本人仍处于活动状态的来源会话；手工清单、他人会话或已删除会话为空
     * @param reportId DIRECT 请求已验证的稳定报表标识；普通或跨报表清单为空
     * @param status 清单当前状态，不用历史任务状态推断业务成功
     */
    public record HistoryPlan(String planId,String conversationId,String reportId,String status) {}
    /**
     * 我的任务列表的最小展示信息；与单任务恢复协议分离，禁止带出幂等键、原始负载或链路凭据。
     * @param id 任务标识，仅可用于读取既有任务
     * @param action 任务动作，取值与 Request.action 一致
     * @param status QUEUED、RUNNING、SUCCEEDED 或 FAILED；SUCCEEDED 不等同于全部条目派单成功
     * @param message 脱敏任务说明；DIRECT 无可验证结果时只给通用进度或不可用说明
     * @param createdAt 持久化创建时间，按此时间与 id 倒序稳定分页
     * @param updatedAt 任务最后更新时间，不参与分页，避免状态变化导致跨页重复
     * @param resultAvailable 是否仍保留业务结果；false 时不得从请求负载重建结果
     * @param plans 当前有权访问的清单链接；DIRECT 结果过期后为空
     */
    public record HistoryEntry(String id,String action,String status,String message,LocalDateTime createdAt,
                               LocalDateTime updatedAt,boolean resultAvailable,List<HistoryPlan> plans) {}
    /**
     * 经当前数据授权过滤的任务历史页；扫描预算与返回条数分开限制。
     * @param rows 本页可读任务，最多请求的 size 条；权限过滤后允许为空
     * @param nextCursor 最后扫描位置及筛选绑定；为空表示已扫描到末尾，换筛选须从首页开始
     */
    public record HistoryPage(List<HistoryEntry> rows,String nextCursor) {}
    /** 服务端分页位置只影响扫描起点，不能替代 SQL 中的租户和用户边界。 */
    private record HistoryCursor(LocalDateTime createdAt,String id,String status,String owner) {}
    /** 本次读取已验证的请求和业务结果，原始任务负载只用于权限检查。 */
    private record ReadableJob(Request request,Map<String,Object> result,List<String> planIds) {}
    private static final Set<String> ACTIONS=Set.of("CONFIRM","RETRY_FAILED","RECONCILE","DIRECT","OP_RETRY_FAILED","OP_RECONCILE");
    private static final Set<String> STATUSES=Set.of("QUEUED","RUNNING","SUCCEEDED","FAILED");
    private static final int HISTORY_SCAN_LIMIT=200;
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final PermissionService permissions;
    private final PlanService plans;
    private final PlanRepository repository;
    private final DispatchService dispatch;
    private final ReportCatalogService catalog;
    private final OperationsWorkbench workbench;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final ScheduledExecutorService heartbeats=Executors.newScheduledThreadPool(2);
    private final Map<String,Future<?>> running=new ConcurrentHashMap<>();
    private volatile boolean closed;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private BusinessMetrics metrics;

    public DispatchJobService(JdbcTemplate jdbc,TransactionOperations tx,PermissionService permissions,PlanService plans,
            PlanRepository repository,DispatchService dispatch,ReportCatalogService catalog,OperationsWorkbench workbench) {
        this.jdbc=jdbc;this.tx=tx;this.permissions=permissions;this.plans=plans;this.repository=repository;
        this.dispatch=dispatch;this.catalog=catalog;this.workbench=workbench;
    }

    /**
     * 接收并持久化一个业务命令；入队事务不执行外部派单。
     * @param user 当前请求的服务端身份
     * @param input 清单动作，或 DIRECT 的报表和记录范围；混用参数会拒绝
     * @param key 调用方在首次发送前保存的稳定幂等键
     * @return 新任务或相同负载的既有任务；终态须另行读取
     * @throws ApiException 参数、授权、队列容量或幂等负载冲突
     */
    public Job submit(CurrentUser user,Request input,String key) {
        if(key==null || !key.matches("[a-zA-Z0-9_-]{16,128}")) throw new ApiException("请提供稳定的任务幂等键");
        if(input==null || !ACTIONS.contains(Objects.toString(input.action(),""))) throw new ApiException("不支持的派单任务");
        Request request=normalize(user,input);
        String payload=JsonUtil.toJson(request);
        var prior=jdbc.queryForList("SELECT * FROM dispatch_job WHERE tenant_id=? AND user_id=? AND idempotency_key=?",user.tenantId(),user.userId(),key);
        if(!prior.isEmpty()) return replay(user,prior.get(0),payload);
        try {
            String id=tx.execute(status->{
                jdbc.update("INSERT INTO dispatch_job_queue(tenant_id) VALUES (?) ON DUPLICATE KEY UPDATE tenant_id=tenant_id",user.tenantId());
                jdbc.queryForObject("SELECT tenant_id FROM dispatch_job_queue WHERE tenant_id=? FOR UPDATE",String.class,user.tenantId());
                Long version=null;
                if(request.planId()!=null) {
                    var row=jdbc.queryForMap("SELECT execution_version FROM dispatch_plan WHERE tenant_id=? AND id=? FOR UPDATE",user.tenantId(),request.planId());
                    version=((Number)row.get("execution_version")).longValue();
                    // A new key must not silently become an alias and later resend a retry.
                    var active=jdbc.queryForList("SELECT * FROM dispatch_job WHERE active_plan=?",request.planId());
                    if(!active.isEmpty()) throw new ApiException(409,"该清单已有在途任务，请刷新执行结果");
                }
                long queued=jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_job WHERE tenant_id=? AND status IN ('QUEUED','RUNNING')",Long.class,user.tenantId());
                if(queued>=100) throw new ApiException(429,"派单任务队列已满，请稍后重试");
                long userQueued=jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_job WHERE tenant_id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",Long.class,user.tenantId(),user.userId());
                if(userQueued>=8) throw new ApiException(429,"您的派单任务过多，请等待已有任务完成");
                String created=JsonUtil.newId();
                jdbc.update("INSERT INTO dispatch_job(id,tenant_id,user_id,plan_id,action,expected_version,idempotency_key,payload_json,trace_id,expires_at) VALUES (?,?,?,?,?,?,?,?,?,TIMESTAMPADD(SECOND,600,NOW(3)))",
                        created,user.tenantId(),user.userId(),request.planId(),request.action(),version,key,payload,TraceIds.current());
                return created;
            });
            return get(user,id);
        } catch(org.springframework.dao.DuplicateKeyException collision) {
            var saved=jdbc.queryForList("SELECT * FROM dispatch_job WHERE tenant_id=? AND user_id=? AND idempotency_key=?",user.tenantId(),user.userId(),key);
            if(saved.isEmpty()) throw new ApiException(409,"该清单已有任务，请刷新执行状态");
            return replay(user,saved.get(0),payload);
        }
    }

    /**
     * 校验并规范化任务负载；人工记录去重排序后再做幂等比对，运维原因先脱敏，避免排列差异产生不同操作。
     */
    private Request normalize(CurrentUser user,Request input) {
        if("DIRECT".equals(input.action())) {
            if(input.planId()!=null || input.recordIds()==null || input.recordIds().isEmpty() || input.recordIds().size()>50)
                throw new ApiException("每次请选择 1～50 条记录");
            if(input.recordIds().stream().anyMatch(id->id==null || id.isBlank() || id.length()>128)) throw new ApiException("记录 ID 无效");
            var report=catalog.requireVisible(user,input.reportId());
            catalog.requireDispatchable(user,report.reportId());
            return new Request(null,"DIRECT",report.reportId(),input.recordIds().stream().map(String::trim).distinct().sorted().toList(),null);
        }
        if(input.planId()==null || input.planId().isBlank() || input.planId().length()>64 || input.reportId()!=null || input.recordIds()!=null)
            throw new ApiException("派单任务参数不合法");
        if(input.action().startsWith("OP_")) workbench.validateAction(user,input.planId(),operation(input.action()),input.reason());
        else plans.pageOwned(user,input.planId(),1,1);
        return new Request(input.planId(),input.action(),null,null,input.action().startsWith("OP_")?SensitiveData.text(input.reason()):null);
    }

    /**
     * 同键只能回放同租户同用户的完全相同负载；禁止把新参数绑定到既有任务。
     */
    private Job replay(CurrentUser user,Map<String,Object> row,String payload) {
        if(!user.userId().equals(row.get("user_id")) || !user.tenantId().equals(row.get("tenant_id")) || !payload.equals(row.get("payload_json")))
            throw new ApiException(409,"幂等键或在途任务对应其他操作，请先刷新并核对");
        return get(user,row.get("id").toString());
    }

    /**
     * 按租户和任务所属用户读取状态，并重新校验当前报表、公司与清单可见范围；result 为空可能尚未完成或已过保留期。
     */
    public Job get(CurrentUser user,String id) {
        var rows=jdbc.queryForList("SELECT * FROM dispatch_job WHERE id=? AND tenant_id=? AND user_id=?",id,user.tenantId(),user.userId());
        if(rows.isEmpty()) throw ApiException.notFound("派单任务不存在");
        var row=rows.get(0);
        ReadableJob readable=readable(user,row);
        return new Job(id,(String)row.get("plan_id"),(String)row.get("action"),(String)row.get("status"),
                readMessage(row,readable),readable.result(),(String)row.get("idempotency_key"));
    }

    /**
     * 统一单任务和历史列表的读取权限；保留的 DIRECT 结果必须逐清单复核，不能只检查报表权限。
     * 无结果时不读取原始记录范围来猜测历史业务事实，消息由 readMessage 降为不含业务数据的任务说明。
     */
    private ReadableJob readable(CurrentUser user,Map<String,Object> row) {
        if(!Objects.equals(user.tenantId(),row.get("tenant_id")) || !Objects.equals(user.userId(),row.get("user_id")))
            throw ApiException.notFound("派单任务不存在");
        Request request=decode(row);
        Map<String,Object> result=row.get("result_json")==null?null:JsonUtil.toMap(row.get("result_json").toString());
        List<String> linked;
        if(request.planId()!=null) {
            if(request.action().startsWith("OP_")) workbench.validateAction(user,request.planId(),"reconcile","读取任务状态");
            else plans.pageOwned(user,request.planId(),1,1);
            linked=List.of(request.planId());
        } else {
            catalog.refreshForValidation();
            var report=catalog.find(request.reportId()).orElseThrow(()->ApiException.notFound("报表不存在"));
            if(!user.tenantId().equals(report.tenantId()) || !user.hasPermission(report.permissionCode())) throw ApiException.notFound("派单任务不存在");
            linked=directPlanIds(result);
            for(String planId:linked) plans.pageOwned(user,planId,1,1);
        }
        return new ReadableJob(request,result,linked);
    }

    /** DIRECT 只从已保存结果提取清单关联；缺失或异常结构不得被当作已经完成权限核对。 */
    private static List<String> directPlanIds(Map<String,Object> result) {
        if(result==null)return List.of();
        if(!(result.get("plans") instanceof List<?> items) || items.size()>50)
            throw new ApiException(502,"任务结果缺少可核对的清单信息");
        var ids=new ArrayList<String>();
        for(Object item:items) {
            if(!(item instanceof Map<?,?> plan) || !(plan.get("planId") instanceof String id)
                    || id.isBlank() || id.length()>64 || ids.contains(id))
                throw new ApiException(502,"任务结果缺少可核对的清单信息");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    /**
     * DIRECT 无结果时没有完整的历史公司范围可供复核，不能返回可能含单据或公司信息的旧错误消息。
     * 保留任务本身的进度；结果过期和失败不伪装成可重新执行的命令。
     */
    private static String readMessage(Map<String,Object> row,ReadableJob readable) {
        if("DIRECT".equals(readable.request().action()) && readable.result()==null) {
            return switch(Objects.toString(row.get("status"),"")) {
                case "QUEUED" -> "任务已接收，等待执行";
                case "RUNNING" -> "任务正在执行，请稍后刷新";
                case "SUCCEEDED" -> "任务结果保留期已到期，关联清单信息不可用";
                default -> "任务未完成或结果已不可用；请刷新报表并核对既有清单，不会自动重发";
            };
        }
        return SensitiveData.text((String)row.get("message"));
    }

    /**
     * 按创建时间和 id 倒序读取本人任务；只扫描有限候选，过滤撤销权限的记录后仍返回扫描游标。
     * 本方法只查询，不触发模型、确认、认领或重试；列表不返回任务负载、幂等键和原始业务结果。
     * @param user 当前身份，SQL 始终按租户和用户限定
     * @param before 上一页返回的游标；首次查询为空，换筛选须清空
     * @param status 精确任务状态过滤；为空表示全部，不接受其他值或空字符串
     * @param size 每页可见任务数，范围 1 至 50
     * @return 当前可见任务和最后扫描位置，空 rows 与非空游标表示还可继续扫描
     */
    public HistoryPage history(CurrentUser user,String before,String status,int size) {
        if(size<1 || size>50 || (status!=null && !STATUSES.contains(status)))
            throw new ApiException("任务历史筛选或每页条数无效");
        HistoryCursor cursor=parseHistoryCursor(user,before,status);
        var args=new ArrayList<Object>();args.add(user.tenantId());args.add(user.userId());
        // 普通清单任务只需要已有 plan_id，不为列表载入整批业务结果；DIRECT 关联最多 50 份清单且仅存在于结果中。
        var sql=new StringBuilder("SELECT id,tenant_id,user_id,plan_id,action,status,message,payload_json,CASE WHEN action='DIRECT' THEN result_json END AS result_json,(result_json IS NOT NULL) AS result_available,created_at,updated_at FROM dispatch_job WHERE tenant_id=? AND user_id=?");
        if(status!=null){sql.append(" AND status=?");args.add(status);}
        if(cursor!=null) {
            sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
            args.add(cursor.createdAt());args.add(cursor.createdAt());args.add(cursor.id());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ").append(HISTORY_SCAN_LIMIT+1);
        var candidates=jdbc.queryForList(sql.toString(),args.toArray());
        var visible=new ArrayList<HistoryEntry>();int scanned=0;
        while(scanned<Math.min(HISTORY_SCAN_LIMIT,candidates.size()) && visible.size()<size) {
            var row=candidates.get(scanned++);
            try {
                ReadableJob readable=readable(user,row);
                var links=new ArrayList<HistoryPlan>();
                for(String planId:readable.planIds()) {
                    var plan=repository.find(planId).orElseThrow(()->ApiException.notFound("清单不存在"));
                    String conversation=plan.getConversationId();
                    if(conversation!=null && (!PermissionService.owns(user,plan.getTenantId(),plan.getUserId())
                            || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_conversation WHERE id=? AND tenant_id=? AND user_id=? AND status='active')",Boolean.class,conversation,user.tenantId(),user.userId()))))
                        conversation=null;
                    links.add(new HistoryPlan(planId,conversation,"DIRECT".equals(readable.request().action())?readable.request().reportId():null,plan.getStatus()));
                }
                visible.add(new HistoryEntry(row.get("id").toString(),row.get("action").toString(),row.get("status").toString(),
                        readMessage(row,readable),historyTime(row,"created_at"),historyTime(row,"updated_at"),
                        Boolean.TRUE.equals(row.get("result_available")) || (row.get("result_available") instanceof Number count && count.intValue()==1),List.copyOf(links)));
            } catch(ApiException denied) {
                // 无权或对象已不可读的记录整条隐藏，不能返回部分 DIRECT 清单或旧错误消息；其他故障必须如实失败。
                if(denied.getCode()!=403 && denied.getCode()!=404)throw denied;
            }
        }
        String next=null;
        if(scanned<candidates.size() && scanned>0) {
            var last=candidates.get(scanned-1);
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(JsonUtil.toJson(new HistoryCursor(
                    historyTime(last,"created_at"),last.get("id").toString(),status,historyOwner(user))).getBytes(StandardCharsets.UTF_8));
        }
        return new HistoryPage(List.copyOf(visible),next);
    }

    /** 游标绑定原筛选和当前用户；严格拒绝畸形结构，权限仍由每次 SQL 和业务范围检查决定。 */
    private static HistoryCursor parseHistoryCursor(CurrentUser user,String before,String status) {
        if(before==null)return null;
        try {
            if(before.length()>1024 || !before.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            var cursor=JsonUtil.MAPPER.readerFor(HistoryCursor.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .<HistoryCursor>readValue(Base64.getUrlDecoder().decode(before));
            if(cursor.createdAt()==null || cursor.createdAt().getYear()<1000 || cursor.createdAt().getYear()>9999
                    || cursor.id()==null || !cursor.id().matches("[a-f0-9]{32}")
                    || !Objects.equals(cursor.status(),status) || !historyOwner(user).equals(cursor.owner()))throw new IllegalArgumentException();
            return cursor;
        } catch(Exception invalid) {throw new ApiException("任务历史游标无效或筛选已变化，请从第一页重新查询");}
    }

    private static String historyOwner(CurrentUser user) {return Digests.sha256(JsonUtil.toJson(List.of(user.tenantId(),user.userId())));}

    /** JDBC 驱动可能返回 Timestamp 或 LocalDateTime；两者均保留数据库毫秒精度，不使用系统时区转换分页位置。 */
    private static LocalDateTime historyTime(Map<String,Object> row,String field) {
        Object value=row.get(field);
        if(value instanceof Timestamp timestamp)return timestamp.toLocalDateTime();
        if(value instanceof LocalDateTime local)return local;
        throw new IllegalStateException("任务时间字段无法读取");
    }

    /**
     * 只查该用户该清单最近已存在的任务；没有任务时返回空，不创建或重新发送命令。
     */
    public Job latest(CurrentUser user,String planId) {
        var rows=jdbc.queryForList("SELECT id FROM dispatch_job WHERE tenant_id=? AND user_id=? AND plan_id=? ORDER BY created_at DESC,id DESC LIMIT 1",user.tenantId(),user.userId(),planId);
        return rows.isEmpty()?null:get(user,rows.get(0).get("id").toString());
    }

    /**
     * 定时恢复过期任务并填充本实例工作槽；SynchronousQueue 不在内存堆积命令，多个实例靠数据库锁各自认领。
     */
    @Scheduled(fixedDelayString="${agent.dispatch-jobs-poll-ms:500}")
    public void poll() {
        if(closed) return;
        try {
            recover();
            for(int i=0;i<4 && workers.getActiveCount()<4;i++) {
                Map<String,Object> row=claim();
                if(row==null) break;
                String id=row.get("id").toString(),token=row.get("claim_token").toString();
                FutureTask<Void> future=new FutureTask<>(()->{ run(row); return null; });
                running.put(id,future);
                try { workers.execute(future); }
                catch(RejectedExecutionException full) {
                    running.remove(id);
                    jdbc.update("UPDATE dispatch_job SET status='QUEUED',claim_token=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND claim_token=?",id,token);
                    break;
                }
            }
        } catch(RuntimeException failure) { log.warn("派单任务扫描暂不可用 ({})",failure.getClass().getSimpleName()); }
    }

    /**
     * 事务内按本实例租户用 SKIP LOCKED 认领一条未超时排队任务，生成独占认领令牌和5分钟租约；其他实例不能同时取得此任务。
     */
    private Map<String,Object> claim() {
        return tx.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM dispatch_job WHERE tenant_id=? AND status='QUEUED' AND expires_at>NOW(3) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",permissions.tenantId());
            if(rows.isEmpty()) return null;
            var row=rows.get(0); String token=JsonUtil.newId();
            jdbc.update("UPDATE dispatch_job SET status='RUNNING',claim_token=?,lease_until=TIMESTAMPADD(SECOND,300,NOW(3)),updated_at=NOW(3) WHERE id=? AND status='QUEUED'",token,row.get("id"));
            row.put("claim_token",token); return row;
        });
    }

    /**
     * 工作线程每30秒续租，执行前重新解析当前授权并验证冻结的清单版本。成功/失败结果仅凭本次令牌条件保存；失去执行权时停止回写。
     */
    private void run(Map<String,Object> row) {
        long started=System.nanoTime();String outcome="FAILED";
        String id=row.get("id").toString(),token=row.get("claim_token").toString();
        String previous=MDC.get("traceId");
        if(row.get("trace_id")!=null) MDC.put("traceId",row.get("trace_id").toString());
        ScheduledFuture<?> heartbeat=heartbeats.scheduleAtFixedRate(()->{
            try {
                if(jdbc.update("UPDATE dispatch_job SET lease_until=TIMESTAMPADD(SECOND,300,NOW(3)),updated_at=NOW(3) WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_until>NOW(3)",id,token)!=1) {
                    Future<?> task=running.get(id); if(task!=null) task.cancel(true);
                }
            } catch(RuntimeException unavailable) { log.warn("派单任务续租暂不可用 job={}",id); }
        },30,30,TimeUnit.SECONDS);
        try {
            var user=permissions.resolve(row.get("user_id").toString());
            if(!user.tenantId().equals(row.get("tenant_id"))) throw ApiException.forbidden("任务租户不匹配");
            Request request=decode(row);
            Long version=row.get("expected_version")==null?null:((Number)row.get("expected_version")).longValue();
            if(version!=null && repository.find(request.planId()).filter(p->Objects.equals(p.getExecutionVersion(),version)).isEmpty())
                throw new ApiException(409,"任务提交后清单执行版本已变化，请刷新后重新处理");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM dispatch_job WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_until>NOW(3))",Boolean.class,id,token)))
                throw new ApiException(409,"派单任务执行权已失效");
            Object result=switch(request.action()) {
                case "CONFIRM" -> dispatch.confirm(user,request.planId(),(String)row.get("trace_id"),version);
                case "RETRY_FAILED" -> dispatch.retryFailed(user,request.planId(),version);
                case "RECONCILE" -> dispatch.reconcile(user,request.planId());
                case "DIRECT" -> dispatch.dispatchDirect(user,request.reportId(),request.recordIds());
                default -> workbench.act(user,request.planId(),operation(request.action()),request.reason(),version);
            };
            jdbc.update("UPDATE dispatch_job SET status='SUCCEEDED',result_json=?,claim_token=NULL,lease_until=NULL,updated_at=NOW(3) WHERE id=? AND status='RUNNING' AND claim_token=?",
                    JsonUtil.toJson(SensitiveData.value(result)),id,token);
            outcome="SUCCEEDED";
        } catch(Exception failure) {
            String message=failure instanceof ApiException ? Objects.toString(SensitiveData.text(failure.getMessage()),"任务未完成，请刷新清单状态") : "任务中断，请刷新清单并核对结果；不会自动重新派单";
            try { jdbc.update("UPDATE dispatch_job SET status='FAILED',message=?,claim_token=NULL,lease_until=NULL,updated_at=NOW(3) WHERE id=? AND status='RUNNING' AND claim_token=?",message.substring(0,Math.min(512,message.length())),id,token); }
            catch(RuntimeException unavailable) { log.warn("派单任务收尾保存失败 job={}",id); }
        } finally {
            heartbeat.cancel(false);running.remove(id);
            if(metrics!=null) {
                try {
                    var actor=permissions.resolve(row.get("user_id").toString());
                    metrics.record(actor,"DISPATCH_JOB_"+row.get("action"),"*",Objects.toString(row.get("expected_version"),""),outcome,started);
                } catch(RuntimeException unavailable) { log.warn("派单任务指标记录未完成 job={}",id); }
            }
            if(previous==null) MDC.remove("traceId"); else MDC.put("traceId",previous);
        }
    }

    /**
     * 排队超10分钟直接失败；运行租约过期时只提示核对，不回到队列。清单自身心跳也过期后才交由恢复处理，避免撤销其他健康执行。
     */
    public void recover() {
        jdbc.update("UPDATE dispatch_job SET status='FAILED',message='任务排队超过10分钟，尚未启动；请刷新后重新确认',updated_at=NOW(3) WHERE tenant_id=? AND status='QUEUED' AND expires_at<=NOW(3) LIMIT 100",permissions.tenantId());
        var rows=jdbc.queryForList("SELECT id,plan_id,expected_version,claim_token FROM dispatch_job WHERE tenant_id=? AND status='RUNNING' AND lease_until<=NOW(3) ORDER BY lease_until LIMIT 20",permissions.tenantId());
        for(var row:rows) tx.executeWithoutResult(status->{
            String plan=(String)row.get("plan_id");
            if(plan!=null) jdbc.queryForList("SELECT id FROM dispatch_plan WHERE id=? FOR UPDATE",plan);
            if(jdbc.update("UPDATE dispatch_job SET status='FAILED',message='任务执行中断，请刷新并核对结果；不会自动重发',claim_token=NULL,lease_until=NULL,updated_at=NOW(3) WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_until<=NOW(3)",row.get("id"),row.get("claim_token"))==1
                    && plan!=null && row.get("expected_version")!=null) {
                var now=java.time.LocalDateTime.now();
                // Do not revoke a healthy execution started by another accepted request.
                repository.markStaleForReview(plan,now.minusMinutes(5),now);
            }
        });
    }

    private static String operation(String action) { return "OP_RETRY_FAILED".equals(action)?"retry-failed":"reconcile"; }
    private static Request decode(Map<String,Object> row) {
        try { return JsonUtil.MAPPER.readValue(row.get("payload_json").toString(),Request.class); }
        catch(Exception invalid) { throw new IllegalStateException("派单任务数据无法读取"); }
    }
    /**
     * 关闭实例的工作线程与续租线程；持久化运行任务随后由租约恢复标为待人工核对，不能自动重发。
     */
    @jakarta.annotation.PreDestroy public void close() {
        closed=true;
        running.values().forEach(task->task.cancel(true));workers.shutdownNow();heartbeats.shutdownNow();
    }
}
