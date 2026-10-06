package com.example.business;

import com.example.report.catalog.query.DispatchStatusWriter;
import com.example.report.common.*;
import com.example.report.dispatch.DispatchGateway.*;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.*;
import com.example.report.security.IdentityStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 真实 HTTP MCP 业务派单的事务边界：复核当前授权、持久化确认、执行轮次、目录及规则，再写入来源业务状态和幂等结果。
 * 同一请求号必须匹配相同操作者与负载；成功结果只回放，结果不明时应查询账本再决定后续操作。
 */
@Service
public class BusinessDispatch {
    private final JdbcTemplate jdbc;
    private final BusinessQueries queries;
    private final DispatchRuleMapper rules;
    private final RuleEngine engine;
    private final IdentityStore identities;
    private final ObjectMapper json;
    public BusinessDispatch(JdbcTemplate jdbc,BusinessQueries queries,DispatchRuleMapper rules,RuleEngine engine,IdentityStore identities,ObjectMapper json) {
        this.jdbc=jdbc;this.queries=queries;this.rules=rules;this.engine=engine;this.identities=identities;this.json=json;
    }
    /**
     * 在 READ_COMMITTED 事务内锁定用户、幂等账本、清单与目录，复核后更新来源记录和最终结果。成功请求回放不再写业务表；执行权失效或负载不同立即拒绝。
     * @param executionVersion 编排服务认领时冻结的执行轮次，必须匹配数据库已确认清单
     */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Outcome submit(CurrentUser caller,String requestId,String reportId,Candidate record,boolean enforceRules,long executionVersion) {
        if(requestId==null || !requestId.matches("[a-zA-Z0-9_-]{1,160}") || record==null
                || !Objects.equals(reportId,record.reportId())) throw new ApiException("派单参数不合法");
        jdbc.queryForList("SELECT user_id FROM app_user WHERE tenant_id=? AND user_id=? FOR UPDATE",caller.tenantId(),caller.userId());
        CurrentUser user=identities.resolve(caller.tenantId(),caller.userId());
        if(!user.companies().contains(record.companyCode())) throw ApiException.forbidden("公司范围超出权限");
        String hash;
        try {hash=Digests.sha256(json.writeValueAsString(Arrays.asList(user.tenantId(),user.userId(),reportId,record,enforceRules)));}
        catch(Exception e){throw new ApiException("派单参数无法编码");}
        jdbc.update("INSERT INTO business_dispatch_request(tenant_id,request_id,operator_id,payload_hash,report_id,record_id,status) VALUES (?,?,?,?,?,?,'PROCESSING') "
                        +"ON DUPLICATE KEY UPDATE request_id=VALUES(request_id)",user.tenantId(),requestId,user.userId(),hash,reportId,record.recordId());
        var prior=jdbc.queryForMap("SELECT * FROM business_dispatch_request WHERE tenant_id=? AND request_id=? FOR UPDATE",user.tenantId(),requestId);
        if(!hash.equals(prior.get("payload_hash")) || !user.userId().equals(prior.get("operator_id"))) throw new ApiException(409,"幂等请求号对应的负载不一致");
        if("SUCCESS".equals(prior.get("status"))) return Outcome.ok();
        verifyConfirmation(user,requestId,record,enforceRules,executionVersion);
        Outcome outcome;
        try {
            var report=queries.require(user,reportId,true);
            if(!report.dispatchEnabled() || !Objects.equals(record.catalogVersion(),report.catalogVersion())) {
                outcome=Outcome.fail("CATALOG_CHANGED","报表定义已变更或已停用派单");
            } else if(!(report.adapter() instanceof DispatchStatusWriter writer)) {
                outcome=Outcome.fail("NOT_SUPPORTED","该报表不支持事务派单");
            } else {
                // Catalog row lock is also taken by rule publication, serializing rule/version changes.
                DispatchRule active=enforceRules?activeRule(user,reportId,record.companyCode()):null;
                boolean success=writer.markDispatchedGuarded(user.tenantId(),record.recordId(),record.companyCode(),LocalDateTime.now(),row ->
                        user.companies().contains(row.companyCode()) && Objects.equals(row.companyCode(),record.companyCode())
                        // 行锁内逐字段核对已确认事实；即使仍命中业务规则，字段变化也不能沿用旧确认。
                        && ConfirmedRecord.matches(record,row,report.fields())
                        && (!enforceRules || (active!=null && Objects.equals(active.getId(),record.ruleId())
                        && Objects.equals(active.getVersion(),record.ruleVersion()) && engine.matches(active.getExpression(),row.facts()))));
                outcome=success?Outcome.ok():Outcome.fail("RECORD_CHANGED","确认后的业务字段、规则或待派单状态已变化，请重新查询并确认");
            }
        } catch(ApiException e) { outcome=Outcome.fail("NOT_AUTHORIZED", "报表不存在、已停用或权限已变更"); }
        jdbc.update("UPDATE business_dispatch_request SET status=?,error_code=?,message=?,updated_at=NOW() WHERE tenant_id=? AND request_id=?",
                outcome.success()?"SUCCESS":"FAILED",outcome.errorCode(),outcome.message(),user.tenantId(),requestId);
        return outcome;
    }
    /**
     * 以持久化清单作为唯一执行授权：确认人、确认时间、EXECUTING状态、执行版本及条目快照必须全部匹配。模型或网络参数本身不能构成确认依据。
     */
    private void verifyConfirmation(CurrentUser user,String requestId,Candidate record,boolean rulesRequired,long executionVersion) {
        var rows=jdbc.queryForList("SELECT i.plan_id FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id "
                +"WHERE p.tenant_id=? AND p.user_id=? AND i.external_request_id=?",user.tenantId(),user.userId(),requestId);
        if(rows.size()!=1) throw ApiException.forbidden("派单缺少已确认清单");
        String planId=(String)rows.get(0).get("plan_id");
        var plan=jdbc.queryForMap("SELECT * FROM dispatch_plan WHERE id=? FOR UPDATE",planId);
        if(!"EXECUTING".equals(plan.get("status")) || plan.get("confirmed_at")==null
                || !user.userId().equals(plan.get("confirmed_by"))
                || ((Number)plan.get("execution_version")).longValue()!=executionVersion) throw ApiException.forbidden("清单未确认或执行权已失效");
        var item=jdbc.queryForMap("SELECT i.*,v.source AS preview_source FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id "
                +"JOIN dispatch_preview v ON v.id=p.preview_id WHERE i.plan_id=? AND i.external_request_id=?",planId,requestId);
        if(!"UNKNOWN".equals(item.get("status")) || !Objects.equals(item.get("report_id"),record.reportId())
                || !Objects.equals(item.get("record_id"),record.recordId()) || !Objects.equals(item.get("company_code"),record.companyCode())
                // 金额/日期映射可以不在fields列表中，顶层事实也必须先绑定持久清单，不能只相信传入值。
                || !amountEquals((java.math.BigDecimal)item.get("amount"),record.amount())
                || !Objects.equals(Objects.toString(item.get("biz_date"),null),Objects.toString(record.date(),null))
                || !Objects.equals(CounterpartyRef.fromSnapshot(Objects.toString(item.get("counterparty_json"),null)),record.counterparty())
                || !Objects.equals(com.example.report.rule.FieldFact.restore(Objects.toString(item.get("fields_json"),null)),record.fields())
                || !numberEquals(item.get("catalog_version"),record.catalogVersion())
                || !numberEquals(item.get("rule_id"),record.ruleId()) || !numberEquals(item.get("rule_version"),record.ruleVersion())
                || rulesRequired=="manual".equals(item.get("preview_source"))) throw ApiException.forbidden("派单内容与确认清单不一致");
    }
    private boolean numberEquals(Object a,Number b) {return a==null?b==null:b!=null && ((Number)a).longValue()==b.longValue();}
    private static boolean amountEquals(java.math.BigDecimal a,java.math.BigDecimal b) {return a==null?b==null:b!=null && a.compareTo(b)==0;}
    private DispatchRule activeRule(CurrentUser user,String reportId,String company) {
        LocalDateTime now=LocalDateTime.now();
        return rules.publishedReportForUpdate(user.tenantId(),reportId).stream()
                .filter(r->company.equals(r.getCompanyCode()) || "*".equals(r.getCompanyCode()))
                .filter(r->(r.getEffectiveFrom()==null || !r.getEffectiveFrom().isAfter(now)) && (r.getEffectiveTo()==null || r.getEffectiveTo().isAfter(now)))
                .sorted(Comparator.<DispatchRule,Boolean>comparing(r->!company.equals(r.getCompanyCode())).thenComparing(DispatchRule::getVersion,Comparator.reverseOrder()))
                .findFirst().orElse(null);
    }
    /**
     * 锁定读等待同请求的业务事务提交，避免把未提交的成功误判为 NOT_FOUND 并引发重发；只读取当前租户当前操作者的账本。
     */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Lookup lookup(CurrentUser user,String requestId) {
        // A locking read waits for an in-flight transaction; an uncommitted success must not
        // be reported as NOT_FOUND, which would incorrectly authorize a retry.
        return jdbc.query("SELECT status,error_code,message FROM business_dispatch_request WHERE tenant_id=? AND request_id=? AND operator_id=? FOR UPDATE",
                (rs,i)->new Lookup(switch(rs.getString("status")){case "SUCCESS"->LookupStatus.SUCCESS;case "FAILED"->LookupStatus.FAILED;default->LookupStatus.UNKNOWN;},rs.getString("error_code"),rs.getString("message")),user.tenantId(),requestId,user.userId())
                .stream().findFirst().orElse(new Lookup(LookupStatus.NOT_FOUND,null,"请求未受理"));
    }
    /**
     * 管理员只能核对本租户已存在清单对应的请求，并须具备清单全部公司和报表权限；返回旧操作者账本，不重新激活原账号或执行派单。
     */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Lookup lookupForOperator(CurrentUser actor,String operatorId,String requestId) {
        if(!actor.admin()) throw ApiException.forbidden("仅管理员可代核对");
        // Bind delegation to an existing plan in this tenant; no account reactivation is needed.
        var rows=jdbc.queryForList("SELECT v.company_codes,v.report_ids FROM dispatch_plan_item i "
                +"JOIN dispatch_plan p ON p.id=i.plan_id JOIN dispatch_preview v ON v.id=p.preview_id "
                +"WHERE p.tenant_id=? AND v.tenant_id=? AND p.user_id=? AND (i.external_request_id=? "
                +"OR (i.external_request_id IS NULL AND CONCAT(p.id,'-',i.id)=?))",
                actor.tenantId(),actor.tenantId(),operatorId,requestId,requestId);
        if(rows.size()!=1) throw ApiException.notFound("请求不存在或无权核对");
        try {
            Set<String> companies=json.readValue(rows.get(0).get("company_codes").toString(),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            List<String> reports=json.readValue(rows.get(0).get("report_ids").toString(),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            if(companies.isEmpty() || reports.isEmpty() || !actor.companies().containsAll(companies)) throw ApiException.forbidden("公司范围超出权限");
            for(String report:reports) queries.requireHistoricalAccess(actor,report);
        } catch(ApiException e) {throw e;}
        catch(Exception invalid){throw new ApiException("清单范围数据不完整，无法代核对");}
        return jdbc.query("SELECT status,error_code,message FROM business_dispatch_request WHERE tenant_id=? AND request_id=? AND operator_id=? FOR UPDATE",
                (rs,i)->new Lookup(switch(rs.getString("status")){case "SUCCESS"->LookupStatus.SUCCESS;case "FAILED"->LookupStatus.FAILED;default->LookupStatus.UNKNOWN;},rs.getString("error_code"),rs.getString("message")),actor.tenantId(),requestId,operatorId)
                .stream().findFirst().orElse(new Lookup(LookupStatus.NOT_FOUND,null,"请求未受理"));
    }
}
