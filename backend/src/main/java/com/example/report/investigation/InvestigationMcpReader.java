package com.example.report.investigation;

import com.example.report.common.ApiException;
import com.example.report.dispatch.DispatchGateway.Lookup;
import com.example.report.mcp.BusinessMcpClient;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import java.util.Map;

/** 调查专用只读核对；请求号与原操作者由服务端映射，权限拒绝和传输故障分开处理。 */
@Service
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationMcpReader {
    private final ObjectProvider<BusinessMcpClient> clients;
    public InvestigationMcpReader(ObjectProvider<BusinessMcpClient> clients) {this.clients=clients;}
    /** 读取当前条目的原请求结果，不调用会改变清单状态的reconcile；管理员仍受业务服务范围复核。 */
    public Lookup lookup(CurrentUser actor,String owner,String requestId,Duration timeout) {
        var client=clients.getIfAvailable();if(client==null) throw new InvestigationFailure("TOOL_UNAVAILABLE","HTTP MCP业务服务未配置");
        if(!actor.userId().equals(owner) && !actor.admin()) throw ApiException.forbidden("无权核对该请求");
        Map<String,Object> args=actor.userId().equals(owner)?Map.of("requestId",requestId):Map.of("requestId",requestId,"requestOperatorId",owner);
        return client.readOnlyCall("dispatch_lookup",actor,args,new TypeReference<Lookup>(){},timeout);
    }
}
