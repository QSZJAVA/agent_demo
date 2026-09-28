package com.example.report.operations;

import com.example.report.permission.PermissionService;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class AccessAuditConfig implements WebMvcConfigurer {
    private final OperationsAudit audit;
    private final PermissionService permissions;
    public AccessAuditConfig(OperationsAudit audit,PermissionService permissions) { this.audit=audit;this.permissions=permissions; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
                String id=req.getHeader(PermissionService.USER_HEADER);
                if(id==null) return true;
                var user=permissions.resolve(id);
                String path=req.getRequestURI();
                if(path.length()>128) path=path.substring(0,128);
                audit.record(user,"HTTP_"+req.getMethod(),path,"ATTEMPT",null);
                return true;
            }
        }).addPathPatterns("/api/agent/conversations/**","/api/dispatch/**","/api/report-catalog/**","/api/rules/**");
    }
}
