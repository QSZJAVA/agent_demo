package com.example.report.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ClientAddressResolverTest {
    private MockHttpServletRequest request(String peer,String forwarded) {
        var request=new MockHttpServletRequest();request.setRemoteAddr(peer);
        if(forwarded!=null) request.addHeader("X-Forwarded-For",forwarded);
        return request;
    }
    @Test void directClientsCannotForgeForwardedAddresses() {
        assertEquals("203.0.113.1",new ClientAddressResolver("").resolve(request("203.0.113.1","198.51.100.2")));
        assertEquals("203.0.113.1",new ClientAddressResolver("10.0.0.0/24").resolve(request("203.0.113.1","198.51.100.2")));
    }
    @Test void trustedHopsAreStrippedButClientSuppliedPrefixIsIgnored() {
        var resolver=new ClientAddressResolver("10.0.0.0/24,127.0.0.1");
        assertEquals("198.51.100.2",resolver.resolve(request("127.0.0.1","1.2.3.4,198.51.100.2,10.0.0.8")));
        assertEquals("198.51.100.3",resolver.resolve(request("10.0.0.8","198.51.100.3")));
    }
    @Test void malformedHeadersAndInvalidConfigurationFailClosed() {
        var resolver=new ClientAddressResolver("127.0.0.1");
        for(String invalid:new String[]{"hostname","198.51.100.2,","999.1.1.1","127.1","198.51.100.2:1234","a".repeat(4097)})
            assertEquals("127.0.0.1",resolver.resolve(request("127.0.0.1",invalid)));
        assertThrows(IllegalArgumentException.class,()->new ClientAddressResolver("localhost"));
        assertThrows(IllegalArgumentException.class,()->new ClientAddressResolver("10.0.0.1/33"));
    }
    @Test void ipv6ProxyCidrsAreSupported() {
        String actual=new ClientAddressResolver("2001:db8:1::/64").resolve(request("2001:db8:1::2","2001:db8:2::3"));
        assertEquals("2001:db8:2:0:0:0:0:3",actual);
    }
    @Test void loginControllerUsesDistinctClientsBehindTheSameProxy() {
        var identities=mock(IdentityStore.class);
        var controller=new LoginController(identities,new ClientAddressResolver("10.0.0.8"));
        controller.login(new LoginController.Login("alice","password"),request("10.0.0.8","198.51.100.2"));
        controller.login(new LoginController.Login("bob","password"),request("10.0.0.8","198.51.100.3"));
        verify(identities).login("alice","password","198.51.100.2");
        verify(identities).login("bob","password","198.51.100.3");
    }
}
