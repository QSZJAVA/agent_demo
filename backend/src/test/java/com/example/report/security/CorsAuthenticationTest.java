package com.example.report.security;
import com.example.report.common.ApiException;
import com.example.report.config.WebConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CorsAuthenticationTest {
    @Configuration @EnableWebMvc @Import(WebConfig.class)
    static class Config { @Bean Endpoint endpoint(){return new Endpoint();} }
    @RestController static class Endpoint { @GetMapping("/api/test") public String test(){return "ok";} }
    @Test void configuredPreflightSucceedsButUntrustedOriginsAndActualUnauthenticatedRequestsFail() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.setEnvironment(new MockEnvironment().withProperty("web.cors-allowed-origins","https://ui.example.test"));
            context.register(Config.class);context.refresh();
            var identities=mock(IdentityStore.class);
            when(identities.authenticate(null)).thenThrow(new ApiException(401,"login required"));
            var cors=context.getBean(WebConfig.class).apiCorsFilter().getFilter();
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(cors,new SessionFilter(identities,new ObjectMapper())).build();
            mvc.perform(options("/api/test").header("Origin","https://ui.example.test")
                    .header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","authorization"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://ui.example.test"));
            mvc.perform(options("/api/test").header("Origin","https://evil.example.test").header("Access-Control-Request-Method","GET"))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            verifyNoInteractions(identities);
            mvc.perform(get("/api/test").header("Origin","https://ui.example.test")).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Access-Control-Allow-Origin","https://ui.example.test"));
            mvc.perform(options("/api/test")).andExpect(status().isUnauthorized());
        }
    }
}
