package com.example.report.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class McpTransportGuardTest {
    final String token=UUID.randomUUID()+"-"+UUID.randomUUID();
    @Test void nonLoopbackHttpRequiresAnExplicitPrivateNetworkOptIn() {
        assertThrows(IllegalArgumentException.class,()->new BusinessMcpClient(new ObjectMapper(),"http://business-service:8090",token));
        try(var client=new BusinessMcpClient(new ObjectMapper(),"http://business-service:8090",token,true)) { assertNotNull(client); }
    }
    @Test void networkOptInDoesNotPermitEmbeddedCredentialsOrMissingAuthentication() {
        assertThrows(IllegalArgumentException.class,()->new BusinessMcpClient(new ObjectMapper(),"http://user:secret@business-service:8090",token,true));
        assertThrows(IllegalArgumentException.class,()->new BusinessMcpClient(new ObjectMapper(),"http://business-service:8090","",true));
    }
}
