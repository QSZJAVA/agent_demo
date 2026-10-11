package com.example.report.workspace;

import com.example.report.common.*;
import com.example.report.dispatch.*;
import com.example.report.investigation.InvestigationAccessPolicy;
import com.example.report.permission.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * 各业务状态机的本人任务投影与站内已读标记；不复制执行状态，不重试、确认或关闭任何业务任务。
 * 提醒标识只随业务状态变化，续租和重复轮询不会制造新提醒；每条记录与已读操作均重新授权。
 */
@Service
public class TaskInboxService {
    private final JdbcTemplate jdbc;
    private final DispatchJobService jobs;
    private final PlanService plans;
    private final QueryWorkspaceService workspace;
    private final ObjectProvider<InvestigationAccessPolicy> investigations;
    private final PermissionService permissions;
    public TaskInboxService(JdbcTemplate jdbc,DispatchJobService jobs,PlanService plans,QueryWorkspaceService workspace,ObjectProvider<InvestigationAccessPolicy> investigations,PermissionService permissions){this.jdbc=jdbc;this.jobs=jobs;this.plans=plans;this.workspace=workspace;this.investigations=investigations;this.permissions=permissions;}
    private static final String SOURCES="""
        SELECT 'DISPATCH' kind,id,status,plan_id target,TIMESTAMPADD(HOUR,-8,created_at) created_at,CAST(expected_version AS CHAR) version FROM dispatch_job WHERE tenant_id=? AND user_id=?
        UNION ALL SELECT CASE WHEN status='PENDING' THEN 'PLAN' ELSE 'ATTENTION' END,id,status,id,TIMESTAMPADD(HOUR,-8,created_at),CAST(execution_version AS CHAR) FROM dispatch_plan WHERE tenant_id=? AND user_id=? AND ((status='PENDING' AND expires_at>NOW(3)) OR status='REVIEW_REQUIRED' OR (status='EXECUTED' AND failed_count>0))
        UNION ALL SELECT 'INVESTIGATION',id,status,plan_id,created_at,COALESCE(stop_reason,'') FROM agent_investigation_run WHERE tenant_id=? AND actor_id=?
        UNION ALL SELECT 'EXPORT',id,status,run_id,created_at,CAST(row_version AS CHAR) FROM query_export_job WHERE tenant_id=? AND user_id=?
        """;
    /**
     * 任务提醒最小投影；进入详情仍由各业务接口复核。
     * @param kind DISPATCH派单任务、PLAN待确认、ATTENTION异常清单、INVESTIGATION调查或EXPORT导出
     * @param id 原业务对象主键
     * @param status 原状态机当前状态
     * @param target 清单或查询运行标识，不携带执行授权
     * @param eventKey 本次状态提醒的稳定摘要
     * @param unread 此状态版本是否尚未标记已读
     * @param actionable 是否需核对、处理失败或查看完成结果
     * @param createdAt 原对象创建时间，UTC
     * @param message 确定性下一步说明，UNKNOWN只引导核对
     */
    public record Item(String kind,String id,String status,String target,String eventKey,boolean unread,boolean actionable,String createdAt,String message) { }
    /** 有界合并分页，权限过滤后的空页仍给下一扫描位置，避免无权对象阻断后续本人任务。 */
    public QueryWorkspaceService.Page<Item> list(CurrentUser user,String kind,boolean unreadOnly,String before) {
        current(user);if(!Set.of("ALL","DISPATCH","PLAN","ATTENTION","INVESTIGATION","EXPORT").contains(kind))throw new ApiException(422,"任务类别无效");
        String binding=Digests.sha256(user.tenantId()+":"+user.userId()+":"+kind+":"+unreadOnly);
        String time="9999-01-01T00:00",key="~";
        if(before!=null && !before.isBlank())try{if(before.length()>512)throw new IllegalArgumentException();String[] p=new String(Base64.getUrlDecoder().decode(before),StandardCharsets.UTF_8).split("\\|",-1);
            if(p.length!=3 || !p[0].equals(binding) || !p[2].matches("[A-Z]+:[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException();time=LocalDateTime.parse(p[1]).toString();key=p[2];
        }catch(Exception failure){throw new ApiException(422,"任务游标不属于当前筛选，请从首页读取");}
        var args=new ArrayList<Object>();for(int i=0;i<4;i++){args.add(user.tenantId());args.add(user.userId());}args.add(kind);args.add(kind);args.add(LocalDateTime.parse(time));args.add(LocalDateTime.parse(time));args.add(key);
        var rows=jdbc.queryForList("SELECT * FROM ("+SOURCES+") tasks WHERE (?='ALL' OR kind=?) AND (created_at<? OR (created_at=? AND CONCAT(kind,':',id)<?)) ORDER BY created_at DESC,kind DESC,id DESC LIMIT 61",args.toArray());
        var visible=new ArrayList<Item>();for(var row:rows.stream().limit(60).toList())try{var item=project(user,row);if(!unreadOnly || item.unread())visible.add(item);}catch(ApiException denied){if(!Set.of(403,404,409,410).contains(denied.getCode()))throw denied;}
        current(user);String cursor=null;if(rows.size()>60){var row=rows.get(59);cursor=Base64.getUrlEncoder().withoutPadding().encodeToString((binding+"|"+QuerySnapshotStore.localTime(row.get("created_at"))+"|"+row.get("kind")+":"+row.get("id")).getBytes(StandardCharsets.UTF_8));}
        return new QueryWorkspaceService.Page<>(visible,cursor);
    }
    /** 标记指定状态已读；若任务已变化返回冲突，不把用户未见的新失败顺带设为已读。 */
    public void read(CurrentUser user,String kind,String id,String expectedKey) {
        current(user);if(!Set.of("DISPATCH","PLAN","ATTENTION","INVESTIGATION","EXPORT").contains(kind))throw new ApiException(422,"任务类别无效");
        var args=new ArrayList<Object>();for(int i=0;i<4;i++){args.add(user.tenantId());args.add(user.userId());}args.add(kind);args.add(id);
        var found=jdbc.queryForList("SELECT * FROM ("+SOURCES+") tasks WHERE kind=? AND id=?",args.toArray());if(found.isEmpty())throw ApiException.notFound("提醒不存在");var item=project(user,found.get(0));
        if(!item.eventKey().equals(expectedKey))throw new ApiException(409,"任务状态已变化，请刷新后阅读");
        current(user);jdbc.update("INSERT IGNORE INTO task_inbox_read(tenant_id,user_id,event_key) VALUES (?,?,?)",user.tenantId(),user.userId(),item.eventKey());
    }
    /** 先经原业务服务授权，再生成最小提醒；状态摘要不包含心跳，已读只对该用户见过的具体状态生效。 */
    private Item project(CurrentUser user,Map<String,Object> row) {
        String kind=row.get("kind").toString(),id=row.get("id").toString(),status=row.get("status").toString(),target=Objects.toString(row.get("target"),null);
        switch(kind){case "DISPATCH"->jobs.get(user,id);case "PLAN","ATTENTION"->status=plans.getOwned(user,id).plan().getStatus();case "EXPORT"->workspace.require(user,target);
            case "INVESTIGATION"->{var access=investigations.getIfAvailable();if(access==null)throw ApiException.notFound("调查入口不可用");access.require(user,target);}default->throw new IllegalStateException();}
        String key=Digests.sha256(JsonUtil.toJson(Arrays.asList(kind,id,status,Objects.toString(row.get("version"),""))));
        boolean unread=jdbc.queryForObject("SELECT COUNT(*) FROM task_inbox_read WHERE tenant_id=? AND user_id=? AND event_key=?",Integer.class,user.tenantId(),user.userId(),key)==0;
        boolean actionable="ATTENTION".equals(kind) || !Set.of("QUEUED","RUNNING").contains(status);
        String message="PLAN".equals(kind)?"请核对既有清单后明确确认；刷新页面不会自动执行"
                :"ATTENTION".equals(kind)?("REVIEW_REQUIRED".equals(status)?"结果未知，请先核对已有结果":"存在失败条目，请先核实原因，再决定是否重试")
                :"EXPORT".equals(kind)?("SUCCEEDED".equals(status)?"完整结果已生成，可在有效期内下载":"FAILED".equals(status)?"导出失败，查看原因后可显式重新提交":"导出进度以原任务为准")
                :"INVESTIGATION".equals(kind)?"打开调查报告查看依据和待核实事项":"查看原清单的逐条结果；任务完成不代表每条派单成功";
        Instant created=QuerySnapshotStore.instant(row.get("created_at"));
        // 各来源已在SQL投影到UTC，展示与分页排序使用同一时区，不能再对旧业务表重复减去偏移。
        return new Item(kind,id,status,target,key,unread,actionable,created.toString(),message);
    }
    private void current(CurrentUser user){var now=permissions.resolve(user.userId());if(!now.tenantId().equals(user.tenantId()) || !now.permissionVersion().equals(user.permissionVersion()))throw ApiException.forbidden("任务权限已变化，请刷新");}
}
