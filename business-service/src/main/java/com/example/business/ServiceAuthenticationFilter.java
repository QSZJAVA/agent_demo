package com.example.business;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 真实 HTTP MCP 的服务间认证与请求边界；验证配置的服务令牌、来源约束和有界请求体。
 * 该令牌授权编排服务连接，用户公司与报表权限仍由工具处理过程校验。
 */
@Component
@Order(-200)
public class ServiceAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] token;
    private final Set<String> origins;
    public ServiceAuthenticationFilter(@Value("${business.service-token}") String token,@Value("${business.allowed-origins:}") String origins) {
        if(token==null || token.length()<32) throw new IllegalArgumentException("BUSINESS_SERVICE_TOKEN 至少 32 位");
        this.token=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
        this.origins=new HashSet<>(Arrays.asList(origins.split(",")));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store"); response.setHeader("X-Content-Type-Options","nosniff");
        if(request.getServletPath().equals("/health")) {chain.doFilter(request,response);return;}
        String origin=request.getHeader("Origin");
        if(origin!=null && !origins.contains(origin)) {response.sendError(403);return;}
        String auth=request.getHeader("Authorization");
        if(auth==null || !MessageDigest.isEqual(token,auth.getBytes(StandardCharsets.UTF_8))) {
            response.setHeader("WWW-Authenticate","Bearer");response.sendError(401);return;
        }
        if(request.getContentLengthLong()>131072) {response.sendError(413);return;}
        // Also bound chunked bodies (Content-Length may be absent).
        if("POST".equals(request.getMethod())) {
            byte[] body=request.getInputStream().readNBytes(131073);
            if(body.length>131072) {response.sendError(413);return;}
            request=new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input=new java.io.ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read(){return input.read();} public boolean isFinished(){return input.available()==0;}
                        public boolean isReady(){return true;} public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
                    };
                }
                @Override public java.io.BufferedReader getReader() {return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
            };
        }
        chain.doFilter(request,response);
    }
}
