package com.example.report.security;

import com.example.report.common.ApiException;
import com.example.report.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/**
 * 解析当前 Demo 的请求会话并建立服务端身份上下文；业务控制器据此取得用户。账号生命周期行为仍按维护范围暂缓处理。
 */
@Component
@Order(-100)
@ConditionalOnProperty(name="security.enabled",havingValue="true")
public class SessionFilter extends OncePerRequestFilter {
    private final IdentityStore identities;
    private final ObjectMapper json;
    public SessionFilter(IdentityStore identities,ObjectMapper json) {this.identities=identities;this.json=json;}
    public static String bearer(HttpServletRequest request) {
        String h=request.getHeader("Authorization");
        return h!=null && h.startsWith("Bearer ") ? h.substring(7) : null;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Cache-Control","no-store");
        String path=request.getServletPath();
        // MVC checks configured origins/methods/headers. A browser preflight carries no session token.
        if(org.springframework.web.cors.CorsUtils.isPreFlightRequest(request)
                || path.equals("/api/auth/login") || path.equals("/api/auth/mode") || path.startsWith("/api/health")) {chain.doFilter(request,response);return;}
        try {
            var user=identities.authenticate(bearer(request));
            HttpServletRequestWrapper wrapped=new HttpServletRequestWrapper(request) {
                @Override public String getHeader(String name) { return PermissionService.USER_HEADER.equalsIgnoreCase(name)?user.userId():super.getHeader(name); }
                @Override public Enumeration<String> getHeaders(String name) {return PermissionService.USER_HEADER.equalsIgnoreCase(name)?Collections.enumeration(List.of(user.userId())):super.getHeaders(name);}
                @Override public Enumeration<String> getHeaderNames() {Set<String> names=new LinkedHashSet<>(Collections.list(super.getHeaderNames()));names.add(PermissionService.USER_HEADER);return Collections.enumeration(names);}
            };
            chain.doFilter(wrapped,response);
        } catch(ApiException e) {
            response.setStatus(e.getCode()); response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(),Map.of("code",e.getCode(),"message",e.getMessage()));
        }
    }
}
