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

/** Durable business mutation: identity + execution fence + rule/version + source write + result in one transaction. */
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
                        && (!enforceRules || (active!=null && Objects.equals(active.getId(),record.ruleId())
                        && Objects.equals(active.getVersion(),record.ruleVersion()) && engine.matches(active.getExpression(),row.facts()))));
                outcome=success?Outcome.ok():Outcome.fail("RECORD_CHANGED","记录、规则版本或待派单状态已变化");
            }
        } catch(ApiException e) { outcome=Outcome.fail("NOT_AUTHORIZED", "报表不存在、已停用或权限已变更"); }
        jdbc.update("UPDATE business_dispatch_request SET status=?,error_code=?,message=?,updated_at=NOW() WHERE tenant_id=? AND request_id=?",
                outcome.success()?"SUCCESS":"FAILED",outcome.errorCode(),outcome.message(),user.tenantId(),requestId);
        return outcome;
    }
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
                || !numberEquals(item.get("catalog_version"),record.catalogVersion())
                || !numberEquals(item.get("rule_id"),record.ruleId()) || !numberEquals(item.get("rule_version"),record.ruleVersion())
                || rulesRequired=="manual".equals(item.get("preview_source"))) throw ApiException.forbidden("派单内容与确认清单不一致");
    }
    private boolean numberEquals(Object a,Number b) {return a==null?b==null:b!=null && ((Number)a).longValue()==b.longValue();}
    private DispatchRule activeRule(CurrentUser user,String reportId,String company) {
        LocalDateTime now=LocalDateTime.now();
        return rules.publishedReportForUpdate(user.tenantId(),reportId).stream()
                .filter(r->company.equals(r.getCompanyCode()) || "*".equals(r.getCompanyCode()))
                .filter(r->(r.getEffectiveFrom()==null || !r.getEffectiveFrom().isAfter(now)) && (r.getEffectiveTo()==null || r.getEffectiveTo().isAfter(now)))
                .sorted(Comparator.<DispatchRule,Boolean>comparing(r->!company.equals(r.getCompanyCode())).thenComparing(DispatchRule::getVersion,Comparator.reverseOrder()))
                .findFirst().orElse(null);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Lookup lookup(CurrentUser user,String requestId) {
        // A locking read waits for an in-flight transaction; an uncommitted success must not
        // be reported as NOT_FOUND, which would incorrectly authorize a retry.
        return jdbc.query("SELECT status,error_code,message FROM business_dispatch_request WHERE tenant_id=? AND request_id=? AND operator_id=? FOR UPDATE",
                (rs,i)->new Lookup(switch(rs.getString("status")){case "SUCCESS"->LookupStatus.SUCCESS;case "FAILED"->LookupStatus.FAILED;default->LookupStatus.UNKNOWN;},rs.getString("error_code"),rs.getString("message")),user.tenantId(),requestId,user.userId())
                .stream().findFirst().orElse(new Lookup(LookupStatus.NOT_FOUND,null,"请求未受理"));
    }
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
