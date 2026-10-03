package com.example.report.mcp;

import com.example.report.dispatch.DispatchGateway;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * 把已确认清单条目转换为真实 HTTP MCP 派单和核对调用。
 * 执行版本直接沿用认领时的快照，禁止临发送时读取新版本来提升旧请求执行权；网络失败交由核对流程处理。
 */
@Component
@ConditionalOnProperty(name="business.remote.enabled", havingValue="true")
public class McpDispatchGateway implements com.example.report.dispatch.AuthenticatedDispatchGateway {
    private final BusinessMcpClient client;
    public McpDispatchGateway(BusinessMcpClient client) { this.client=client; }
    @Override public Outcome dispatch(DispatchRequest request) {
        throw new IllegalStateException("MCP 派单必须绑定操作者");
    }
    @Override public Outcome dispatch(CurrentUser user, DispatchRequest request) {
        if (!user.tenantId().equals(request.tenantId())) throw new IllegalArgumentException("租户不匹配");
        if (request.executionVersion()<1) throw new IllegalArgumentException("MCP 派单缺少原认领执行版本");
        return client.call("dispatch_submit",user,Map.of("requestId",request.externalRequestId(),
                "reportId",request.report().reportId(),"record",request.record(),"enforceRules",request.enforceRules(),"executionVersion",request.executionVersion()),new TypeReference<>() {});
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
