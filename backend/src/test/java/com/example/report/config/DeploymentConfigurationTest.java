package com.example.report.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-file deployment contracts only; does not assert a Docker runtime has been accepted. */
class DeploymentConfigurationTest {
    final Path root=Path.of(System.getProperty("user.dir")).getParent();
    @SuppressWarnings("unchecked") Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    Map<String,Object> services() throws Exception { return map(map(new Yaml().load(Files.readString(root.resolve("docker-compose.yml")))).get("services")); }
    @Test void businessMigrationAndAuthenticatedAgentHaveOrderedHealthDependencies() throws Exception {
        var services=services();assertEquals(Set.of("mysql","redis","business-service","backend","frontend"),services.keySet());
        var backend=map(services.get("backend"));var env=map(backend.get("environment"));
        assertEquals("service_healthy",map(map(backend.get("depends_on")).get("business-service")).get("condition"));
        assertEquals("real,mcp",env.get("SPRING_PROFILES_ACTIVE"));assertEquals("active",env.get("SEMANTIC_MODE"));
        assertEquals("true",env.get("SECURITY_ENABLED"));assertEquals("false",env.get("SPRING_FLYWAY_ENABLED"));
        assertEquals("http://business-service:8090",env.get("BUSINESS_MCP_URL"));
        var business=map(map(services.get("business-service")).get("environment"));
        assertEquals("0.0.0.0",business.get("SERVER_ADDRESS"));assertEquals("8090",business.get("SERVER_PORT"));
        assertFalse(business.containsKey("SPRING_FLYWAY_ENABLED"));
    }
    @Test void privateBusinessNetworkAndFrontendKeepServiceSecretsAwayFromBrowser() throws Exception {
        var services=services();var compose=map(new Yaml().load(Files.readString(root.resolve("docker-compose.yml"))));
        assertEquals(true,map(map(compose.get("networks")).get("demo-private")).get("internal"));
        for(String name:List.of("mysql","redis","business-service","backend")) assertFalse(map(services.get(name)).containsKey("ports"));
        var front=map(map(services.get("frontend")).get("environment"));
        assertFalse(front.containsKey("LLM_API_KEY"));assertFalse(front.containsKey("BUSINESS_SERVICE_TOKEN"));assertFalse(front.containsKey("DB_PASSWORD"));
        String nginx=Files.readString(root.resolve("frontend/nginx.conf.template"));
        assertFalse(nginx.contains("auth_basic"));assertTrue(nginx.contains("Authorization     $http_authorization"));
        assertTrue(nginx.contains("proxy_buffering    off"));
    }
    @Test void ciBuildsBothModulesAndCapturesBothReports() throws Exception {
        var workflow=map(new Yaml().load(Files.readString(root.resolve(".github/workflows/regression.yml"))));
        var job=map(map(workflow.get("jobs")).get("regression"));
        assertEquals("true",map(job.get("env")).get("MCP_IT"));
        var steps=(List<?>)job.get("steps");
        assertTrue(steps.stream().map(this::map).anyMatch(s->Objects.toString(s.get("run"),"").contains("backend/mvnw -f pom.xml verify")));
        var upload=steps.stream().map(this::map).filter(s->Objects.toString(s.get("uses"),"").startsWith("actions/upload-artifact@")).findFirst().orElseThrow();
        String paths=map(upload.get("with")).get("path").toString();assertTrue(paths.contains("backend/target/surefire-reports"));assertTrue(paths.contains("business-service/target/surefire-reports"));
    }
}
