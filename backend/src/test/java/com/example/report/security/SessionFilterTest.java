package com.example.report.security;
import com.example.report.permission.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SessionFilterTest {
    @Test void mcpDeploymentRejectsDemoAuthentication() {
        assertThrows(IllegalStateException.class,()->new com.example.report.mcp.McpDeploymentGuard(false));
        assertDoesNotThrow(()->new com.example.report.mcp.McpDeploymentGuard(true));
    }
    @Test void replacesSpoofedUserHeaderWithAuthenticatedIdentity() throws Exception {
        IdentityStore identities=mock(IdentityStore.class);
        when(identities.authenticate("valid")).thenReturn(new CurrentUser("T001","reader","Reader",Set.of("A"),Set.of("report:sales"),false));
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/report/sales/page");request.setServletPath("/api/report/sales/page");
        request.addHeader("Authorization","Bearer valid");request.addHeader("X-User-Id","admin");
        new SessionFilter(identities,new ObjectMapper()).doFilter(request,new MockHttpServletResponse(),(r,s)->assertEquals("reader",((jakarta.servlet.http.HttpServletRequest)r).getHeader("X-User-Id")));
    }
    @Test void demoHeaderAloneCannotAuthenticate() throws Exception {
        IdentityStore identities=mock(IdentityStore.class);when(identities.authenticate(null)).thenThrow(new com.example.report.common.ApiException(401,"请登录"));
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/report/sales/page");request.setServletPath("/api/report/sales/page");request.addHeader("X-User-Id","admin");
        MockHttpServletResponse response=new MockHttpServletResponse();
        new SessionFilter(identities,new ObjectMapper()).doFilter(request,response,(r,s)->fail("unauthenticated request reached controller"));assertEquals(401,response.getStatus());
    }
}
