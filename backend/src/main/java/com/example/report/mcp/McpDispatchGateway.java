package com.example.report.mcp;

import com.example.report.dispatch.DispatchGateway;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@ConditionalOnProperty(name="business.remote.enabled", havingValue="true")
public class McpDispatchGateway implements com.example.report.dispatch.AuthenticatedDispatchGateway {
    private final BusinessMcpClient client;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public McpDispatchGateway(BusinessMcpClient client, org.springframework.jdbc.core.JdbcTemplate jdbc) { this.client=client;this.jdbc=jdbc; }
    @Override public Outcome dispatch(DispatchRequest request) {
        throw new IllegalStateException("MCP 派单必须绑定操作者");
    }
    @Override public Outcome dispatch(CurrentUser user, DispatchRequest request) {
        if (!user.tenantId().equals(request.tenantId())) throw new IllegalArgumentException("租户不匹配");
        Long version=jdbc.queryForObject("SELECT p.execution_version FROM dispatch_plan p JOIN dispatch_plan_item i ON i.plan_id=p.id WHERE p.tenant_id=? AND p.user_id=? AND i.external_request_id=?",Long.class,user.tenantId(),user.userId(),request.externalRequestId());
        return client.call("dispatch_submit",user,Map.of("requestId",request.externalRequestId(),
                "reportId",request.report().reportId(),"record",request.record(),"enforceRules",request.enforceRules(),"executionVersion",version),new TypeReference<>() {});
    }
    @Override public Lookup lookup(CurrentUser user,String requestId) {
        try { return client.call("dispatch_lookup",user,Map.of("requestId",requestId),new TypeReference<>() {}); }
        catch (RuntimeException e) { return new Lookup(LookupStatus.UNKNOWN,null,"业务服务暂时无法核对，请稍后重试"); }
    }
    @Override public Lookup lookupForOperator(CurrentUser actor,String operatorId,String requestId) {
        if(!actor.admin()) throw com.example.report.common.ApiException.forbidden("仅管理员可代核对");
        try { return client.call("dispatch_lookup",actor,Map.of("requestId",requestId,"requestOperatorId",operatorId),new TypeReference<>() {}); }
        catch (RuntimeException e) { return new Lookup(LookupStatus.UNKNOWN,null,"业务服务暂时无法核对，请稍后重试"); }
    }
}
