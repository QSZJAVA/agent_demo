package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.entity.DispatchPlan;
import com.example.report.operations.SensitiveData;
import com.example.report.permission.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** 按已授权清单取得有界当前事实；规则来自条目执行快照，不读取整段聊天，不猜测缺失请求号。 */
@Service
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationFacts {
    private final JdbcTemplate jdbc;
    private final InvestigationAccessPolicy access;
    private final InvestigationProperties props;
    public InvestigationFacts(JdbcTemplate jdbc, InvestigationAccessPolicy access, InvestigationProperties props) {
        this.jdbc=jdbc;this.access=access;this.props=props;
    }
    /** 规范化当前请求；null选择全部异常，超过上限必须明确选择子集，无异常不调用模型。 */
    public List<String> select(CurrentUser user, String planId, List<String> requested) {
        var plan=access.require(user,planId);
        if (!Set.of("EXECUTED","REVIEW_REQUIRED").contains(plan.getStatus())) throw new ApiException(409,"请等待清单执行结束或进入待核对状态后调查");
        if(requested!=null && (requested.isEmpty() || requested.size()>props.getMaxItems()
                || requested.stream().anyMatch(Objects::isNull))) throw new ApiException("请选择1～"+props.getMaxItems()+"条异常条目");
        if(requested!=null) requested.forEach(InvestigationFacts::itemId);
        // 明确选择只读取这最多10个ID；清单很大时也不能漏掉排在后面的已选条目。
        var parameters=new ArrayList<Object>();parameters.add(planId);
        String selection=requested==null?"":" AND id IN ("+String.join(",",Collections.nCopies(requested.size(),"?"))+")";
        if(requested!=null) parameters.addAll(requested);parameters.add(props.getMaxItems()+1);
        var eligible=jdbc.queryForList("SELECT id FROM dispatch_plan_item WHERE plan_id=? AND status IN ('FAILED','SKIPPED','UNKNOWN','PENDING')"+selection+" ORDER BY id LIMIT ?",Long.class,parameters.toArray());
        var ids=eligible.stream().map(Object::toString).toList();
        if(requested==null && ids.size()>props.getMaxItems()) throw new ApiException(413,"异常条目较多，请明确选择最多"+props.getMaxItems()+"条");
        var selected=requested==null?ids:requested.stream().distinct().sorted(Comparator.comparingLong(Long::parseLong)).toList();
        if(!ids.containsAll(selected)) throw ApiException.notFound("选择包含不属于清单的异常条目");
        return selected;
    }
    /** 在调用方短事务中冻结当前条目、规则与最多每条20个事件；必须锁定清单与条目以保持同一事实时点。 */
    public Map<String,Object> snapshot(CurrentUser actor, String planId, List<String> ids, boolean lock) {
        DispatchPlan plan=access.require(actor,planId);
        var p=jdbc.queryForMap("SELECT status,execution_version,item_count,success_count,failed_count FROM dispatch_plan WHERE id=? AND tenant_id=?"+(lock?" FOR UPDATE":""),planId,actor.tenantId());
        var items=new ArrayList<Map<String,Object>>(); int index=0;
        for(String id:ids) {
            var rows=jdbc.queryForList("SELECT id,report_id,record_id,doc_no,company_code,label,status,error_code,error_message,attempt_count,external_request_id,rule_snapshot,updated_at FROM dispatch_plan_item WHERE plan_id=? AND id=?"+(lock?" FOR UPDATE":""),planId,id);
            if(rows.size()!=1) throw ApiException.notFound("调查条目不存在");
            var raw=rows.get(0); var item=new LinkedHashMap<String,Object>();
            item.put("itemRef","I"+(++index)); item.put("itemId",id); item.put("reportId",raw.get("report_id")); item.put("recordId",raw.get("record_id"));
            item.put("docNo",SensitiveData.text(Objects.toString(raw.get("doc_no"),""))); item.put("companyCode",raw.get("company_code"));
            item.put("label",bounded(Objects.toString(raw.get("label"),""),160)); item.put("status",raw.get("status")); item.put("errorCode",raw.get("error_code"));
            item.put("errorMessage",bounded(Objects.toString(raw.get("error_message"),""),300)); item.put("attemptCount",raw.get("attempt_count"));
            item.put("requestId",raw.get("external_request_id")); item.put("updatedAt",businessTime(raw.get("updated_at")));
            item.put("rule",raw.get("rule_snapshot")==null?null:JsonUtil.toMap(raw.get("rule_snapshot").toString()));
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM trace_event WHERE tenant_id=? AND plan_id=? AND plan_item_id=? AND event_type<>'MESSAGE'",Long.class,actor.tenantId(),planId,id);
            var events=jdbc.queryForList("SELECT id,event_type,outcome,attempt_count,payload,created_at FROM trace_event WHERE tenant_id=? AND plan_id=? AND plan_item_id=? AND event_type<>'MESSAGE' ORDER BY id DESC LIMIT 20",actor.tenantId(),planId,id);
            var safeEvents=new ArrayList<Map<String,Object>>();
            for(var event:events) {
                var safe=new LinkedHashMap<String,Object>();
                safe.put("eventId",event.get("id").toString());safe.put("type",event.get("event_type"));safe.put("outcome",event.get("outcome"));safe.put("attemptCount",event.get("attempt_count"));safe.put("at",businessTime(event.get("created_at")));
                // 仅提供确定性执行字段；自由文本payload不可冒充工具指令或带出无关会话信息。
                var payload=JsonUtil.toMap(Objects.toString(event.get("payload"),"{}"));
                for(String key:List.of("errorCode","message","reason","status")) if(payload.containsKey(key)) safe.put(key,bounded(Objects.toString(payload.get(key),""),200));
                safeEvents.add(safe);
            }
            item.put("events",safeEvents);item.put("eventCount",count);item.put("eventsTruncated",count>20);items.add(item);
        }
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("status",p.get("status"));snapshot.put("executionVersion",((Number)p.get("execution_version")).longValue());snapshot.put("ownerId",plan.getUserId());
        snapshot.put("counts",Map.of("total",p.get("item_count"),"success",p.get("success_count"),"nonSuccess",p.get("failed_count")));
        snapshot.put("items",items);snapshot.put("snapshotAt",java.time.Instant.now().toString());snapshot.put("fingerprint",fingerprint(snapshot));
        if(JsonUtil.toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2*1024*1024) throw new ApiException(413,"SNAPSHOT_TOO_LARGE：请缩小调查范围");
        return snapshot;
    }
    /** 指纹不含观察时间，覆盖状态、规则及事件内容；来源变化后不能把旧报告描述为当前结果。 */
    public static String fingerprint(Map<String,Object> snapshot) {
        var source=new LinkedHashMap<>(snapshot);source.remove("snapshotAt");source.remove("fingerprint");return InvestigationJson.hash(source);
    }
    public static String bounded(String value,int max) { String safe=SensitiveData.text(value);return safe.length()>max?safe.substring(0,max):safe; }
    /** 当前条目标识为正数BIGINT字符串；越界输入是请求错误，不能传播成服务端500。 */
    public static String itemId(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw new ApiException("条目标识无效");
        try {Long.parseLong(value);return value;} catch(NumberFormatException error) {throw new ApiException("条目标识超出范围");}
    }
    private String businessTime(Object value) {
        if(value==null) return "";
        var local=value instanceof java.sql.Timestamp timestamp?timestamp.toLocalDateTime():java.time.LocalDateTime.parse(value.toString().replace(' ','T'));
        return local.atZone(java.time.ZoneId.of(props.getBusinessTimezone())).toInstant().toString();
    }
}
