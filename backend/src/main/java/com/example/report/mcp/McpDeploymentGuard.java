package com.example.report.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Remote business credentials must never be combined with spoofable demo browser identities. */
@Component
@ConditionalOnProperty(name="business.remote.enabled",havingValue="true")
public class McpDeploymentGuard {
    public McpDeploymentGuard(@Value("${security.enabled:false}") boolean authenticated) {
        if(!authenticated) throw new IllegalStateException("远程 MCP 模式必须启用 security.enabled=true，不能使用演示身份认证");
    }
}
