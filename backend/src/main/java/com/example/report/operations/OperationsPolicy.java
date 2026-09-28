package com.example.report.operations;

import com.example.report.common.*;
import com.example.report.permission.CurrentUser;
import com.example.report.catalog.ReportResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Read-through policies: all instances use the committed version, with CAS on writes and monotonic rollback. */
@Service
public class OperationsPolicy {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.example.report.catalog.ReportCatalog catalog;
    private final JdbcTemplate jdbc;
    private final OperationsAudit audit;
    public OperationsPolicy(JdbcTemplate jdbc, OperationsAudit audit) { this.jdbc=jdbc; this.audit=audit; }
    public record Policy(String key, long version, Map<String,Object> payload) { }
    public record Change(long expectedVersion, Map<String,Object> payload, String reason) { }
    public Policy get(String tenant, String key) {
        var rows = jdbc.query("SELECT version,payload FROM operations_policy WHERE tenant_id=? AND policy_key=?",
                (rs,n) -> new Policy(key,rs.getLong(1),JsonUtil.toMap(rs.getString(2))),tenant,key);
        return rows.isEmpty() ? new Policy(key,0,defaults(key)) : rows.get(0);
    }
    private static Map<String,Object> defaults(String key) {
        if (key.equals("evaluation")) return Map.of("samples",new ResolverEvaluation().samples());
        if (key.equals("resolver")) return Map.of("percent",0,"fuzzyThreshold",0.6,"ambiguityMargin",0.15);
        if (key.equals("retention")) return Map.of("conversationDays",365,"resultDays",365,"metricDays",90,"auditDays",365);
        return Map.of("percent",100);
    }
    public List<Map<String,Object>> history(CurrentUser user, String key) {
        validateKey(key);
        return jdbc.queryForList("SELECT version,payload,created_by,created_at FROM operations_policy_revision WHERE tenant_id=? AND policy_key=? ORDER BY version DESC LIMIT 100",user.tenantId(),key);
    }
    @Transactional
    public Policy save(CurrentUser user, String key, Change change) {
        requireAdmin(user); validateKey(key); requireReason(change.reason()); validate(key,change.payload());
        String report = key.startsWith("catalog:") ? key.substring(8) : null;
        if (report != null) {
            var rows=jdbc.queryForList("SELECT report_id FROM report_definition WHERE tenant_id=? AND report_id=? FOR UPDATE",user.tenantId(),report);
            if(rows.isEmpty()) throw ApiException.notFound("报表不存在");
        }
        // Seed baseline so the first change can also be rolled back; insert locks serialize first writers.
        jdbc.update("INSERT IGNORE INTO operations_policy VALUES (?,?,0,?,?,NOW(3))",user.tenantId(),key,JsonUtil.toJson(defaults(key)),user.userId());
        var current = jdbc.queryForMap("SELECT version,payload FROM operations_policy WHERE tenant_id=? AND policy_key=? FOR UPDATE",user.tenantId(),key);
        long version = ((Number)current.get("version")).longValue();
        if (version != change.expectedVersion()) throw new ApiException(409,"配置已被其他管理员修改，请刷新后重试");
        jdbc.update("INSERT IGNORE INTO operations_policy_revision(tenant_id,policy_key,version,payload,created_by,created_at) VALUES (?,?,?,?,?,NOW(3))",user.tenantId(),key,version,current.get("payload"),user.userId());
        long next=version+1;
        String payload=JsonUtil.toJson(change.payload());
        jdbc.update("UPDATE operations_policy SET version=?,payload=?,updated_by=?,updated_at=NOW(3) WHERE tenant_id=? AND policy_key=?",next,payload,user.userId(),user.tenantId(),key);
        jdbc.update("INSERT INTO operations_policy_revision(tenant_id,policy_key,version,payload,created_by,created_at) VALUES (?,?,?,?,?,NOW(3))",user.tenantId(),key,next,payload,user.userId());
        audit.record(user,"POLICY_CHANGE",key,"SUCCESS",change.reason());
        if (report != null) {
            jdbc.update("UPDATE report_definition SET catalog_version=catalog_version+1,updated_by=?,updated_at=NOW(3) WHERE tenant_id=? AND report_id=?",user.userId(),user.tenantId(),report);
            if(catalog!=null) catalog.broadcastRefresh();
        }
        return new Policy(key,next,change.payload());
    }
    @Transactional
    public Policy rollback(CurrentUser user,String key,long target,long expected,String reason) {
        requireAdmin(user); validateKey(key);
        var values=jdbc.queryForList("SELECT payload FROM operations_policy_revision WHERE tenant_id=? AND policy_key=? AND version=?",String.class,user.tenantId(),key,target);
        if(values.isEmpty()) throw ApiException.notFound("配置版本不存在");
        return save(user,key,new Change(expected,JsonUtil.toMap(values.get(0)),reason));
    }
    public boolean visible(CurrentUser user,String report) {
        return included(user,"catalog:"+report,get(user.tenantId(),"catalog:"+report));
    }
    public Set<String> excludedReports(CurrentUser user) {
        var rows=jdbc.query("SELECT policy_key,version,payload FROM operations_policy WHERE tenant_id=? AND policy_key LIKE 'catalog:%'",
                (rs,n)->new Policy(rs.getString(1),rs.getLong(2),JsonUtil.toMap(rs.getString(3))),user.tenantId());
        var excluded=new HashSet<String>();
        for(Policy p:rows) if(!included(user,p.key(),p)) excluded.add(p.key().substring(8));
        return excluded;
    }
    public static boolean included(CurrentUser user,String key,Policy policy) {
        int percent=((Number)policy.payload().getOrDefault("percent",100)).intValue();
        int bucket=(int)(Long.parseUnsignedLong(Digests.sha256(user.tenantId()+"|"+key+"|"+user.userId()).substring(0,8),16)%100);
        return bucket<percent;
    }
    public ReportResolver resolver(CurrentUser user,ReportResolver baseline) {
        Policy p=get(user.tenantId(),"resolver");
        if(!included(user,"resolver",p)) return baseline;
        return new ReportResolver(((Number)p.payload().get("fuzzyThreshold")).doubleValue(),((Number)p.payload().get("ambiguityMargin")).doubleValue());
    }
    public static void requireAdmin(CurrentUser user) {
        if(user==null || !user.admin()) throw ApiException.forbidden("只有管理员可以执行该操作");
    }
    public static void requireReason(String reason) {
        if(reason==null || reason.isBlank() || reason.length()>512) throw new ApiException("请填写 1～512 字的操作原因");
    }
    public static void validateKey(String key) {
        if(key==null || !(key.equals("evaluation")||key.equals("resolver")||key.equals("retention")||key.matches("catalog:[a-z][a-z0-9-]{2,63}"))) throw new ApiException("不支持的策略");
    }
    public static void validate(String key,Map<String,Object> payload) {
        if(payload==null) throw new ApiException("配置不能为空");
        if(JsonUtil.toJson(payload).length()>1_000_000) throw new ApiException("策略内容不能超过 1 MB");
        if (key.equals("evaluation")) {
            if (!payload.keySet().equals(Set.of("samples")) || !(payload.get("samples") instanceof List<?> rows) || rows.isEmpty() || rows.size()>500)
                throw new ApiException("评估集需要 1～500 条样本");
            try {
                for (Object row:rows) {
                    if(row instanceof Map<?,?> m && (!Set.of("query","expected","reports","visible").containsAll(m.keySet())
                            || !m.keySet().containsAll(Set.of("query","expected","reports")))) throw new IllegalArgumentException();
                    var sample=JsonUtil.MAPPER.convertValue(row,ResolverEvaluation.Sample.class);
                    if(sample.query()==null||sample.query().length()>1000||sample.reports()==null
                            || !Set.of("EXACT","ALIAS","FUZZY","AMBIGUOUS","ALL","NONE").contains(sample.expected())
                            || sample.reports().size()>100 || (sample.visible()!=null&&sample.visible().size()>100)) throw new IllegalArgumentException();
                    var ids=new ArrayList<>(sample.reports());
                    if(sample.visible()!=null) ids.addAll(sample.visible());
                    if(ids.stream().anyMatch(id->id==null||!id.matches("[a-z][a-z0-9-]{2,63}"))) throw new IllegalArgumentException();
                    if(!sample.query().equals(SensitiveData.text(sample.query()))) throw new IllegalArgumentException();
                }
            } catch(RuntimeException failure) { throw new ApiException("评估样本字段无效，或包含未脱敏的个人信息"); }
            return;
        }
        Set<String> allowed=key.equals("retention")?Set.of("conversationDays","resultDays","metricDays","auditDays"):
                key.equals("resolver")?Set.of("percent","fuzzyThreshold","ambiguityMargin"):Set.of("percent");
        if(!payload.keySet().equals(allowed)) throw new ApiException("策略字段不完整或包含未知字段");
        for(String field:allowed) {
            Object v=payload.get(field);
            if(!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new ApiException("配置必须为有效数字");
            double x=n.doubleValue();
            if(field.endsWith("Days")) {
                if(x<1||x>3650||x!=Math.rint(x)) throw new ApiException("留存天数必须为 1～3650 的整数");
            } else if(field.equals("percent")) {
                if(x<0||x>100||x!=Math.rint(x)) throw new ApiException("灰度比例必须为 0～100 的整数");
            } else if(x<=0||x>1) throw new ApiException("解析阈值必须大于 0 且不超过 1");
        }
    }
}
