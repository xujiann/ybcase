package cn.ybcase.bureau;

import cn.ybcase.bureau.common.AuditClientAddress;
import cn.ybcase.bureau.common.BureauAuditFilter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditClientAddressTest {
    MockHttpServletRequest request(String remote,String xff,String real) {
        var r=new MockHttpServletRequest("POST","/api/audit-address-test");r.setRemoteAddr(remote);
        if(xff!=null)r.addHeader("X-Forwarded-For",xff);if(real!=null)r.addHeader("X-Real-IP",real);return r;
    }
    @Test void directSpoofedForwardingCannotReachStoredAuditAddress() throws Exception {
        var resolver=new AuditClientAddress("");var r=request("198.51.100.4","203.0.113.99","203.0.113.98");
        assertEquals("198.51.100.4",resolver.resolve(r));
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        var jdbc=mock(JdbcTemplate.class);var filter=new BureauAuditFilter(jdbc,resolver);
        filter.doFilter(r,new MockHttpServletResponse(),(req,res)->{});
        verify(jdbc).update(anyString(),isNull(),eq("POST"),eq("/api/audit-address-test"),eq(200),eq("198.51.100.4"),isNull());
    }
    @Test void trustedSingleProxyStripsMaliciousLeftPrefixAtActualUntrustedHop() {
        var resolver=new AuditClientAddress("10.0.0.2");
        assertEquals("198.51.100.4",resolver.resolve(request("10.0.0.2","198.51.100.4",null)));
        assertEquals("198.51.100.4",resolver.resolve(request("10.0.0.2","203.0.113.99, 198.51.100.4",null)));
        assertEquals("10.0.0.3",resolver.resolve(request("10.0.0.3","198.51.100.4",null)),"unlisted proxy cannot claim a source");
    }
    @Test void multipleTrustedHopsAndIpv6CidrAreParsedWithoutNames() {
        var resolver=new AuditClientAddress("10.0.0.0/24,2001:db8:1::/48");
        assertEquals("198.51.100.4",resolver.resolve(request("10.0.0.2","203.0.113.99,198.51.100.4,10.0.0.3",null)));
        assertEquals("2001:db8:2::7",resolver.resolve(request("2001:db8:1::2","2001:db8:2::7,2001:db8:1::3",null)));
        var repeated=request("10.0.0.2","198.51.100.4",null);repeated.addHeader("X-Forwarded-For","10.0.0.3");
        assertEquals("198.51.100.4",resolver.resolve(repeated));
    }
    @Test void malformedChainsAlwaysFallBackToPeerRatherThanRealIp() {
        var resolver=new AuditClientAddress("10.0.0.2");
        for(String invalid:new String[]{"localhost","bad,198.51.100.4","198.51.100.4,","198.51.100.999","198.51.100.4:1234","[2001:db8::1]","fe80::1%eth0","2001:::1","1.2.3.4::","::ffff:999.1.1.1"})
            assertEquals("10.0.0.2",resolver.resolve(request("10.0.0.2",invalid,"203.0.113.9")),invalid);
    }
    @Test void realIpFallbackOnlyAppliesToTrustedPeerWithoutForwardedChain() {
        var resolver=new AuditClientAddress("10.0.0.2");
        assertEquals("198.51.100.4",resolver.resolve(request("10.0.0.2",null,"198.51.100.4")));
        assertEquals("198.51.100.4",resolver.resolve(request("10.0.0.2"," "," 198.51.100.4 ")));
        assertEquals("10.0.0.3",resolver.resolve(request("10.0.0.3",null,"198.51.100.4")));
        assertEquals("10.0.0.2",resolver.resolve(request("10.0.0.2",null,"localhost")));
        assertEquals("198.51.100.4",resolver.resolve(request("10.0.0.2","198.51.100.4","203.0.113.9")));
    }
    @Test void malformedProxyConfigurationFailsClosedAndChainLengthIsBounded() {
        for(String bad:new String[]{"localhost","10.0.0.1/33","2001:db8::/129","10.0.0.1/not-a-prefix","10.0.0.1,,10.0.0.2"})
            assertThrows(IllegalArgumentException.class,()->new AuditClientAddress(bad),bad);
        var resolver=new AuditClientAddress("10.0.0.2");
        assertEquals("10.0.0.2",resolver.resolve(request("10.0.0.2",String.join(",",java.util.Collections.nCopies(65,"198.51.100.4")),null)));
        assertEquals("10.0.0.2",resolver.resolve(request("10.0.0.2","x".repeat(8193),null)));
    }
    @Test void anonymousLoginUsesAttemptedAccountButAuthenticatedBusinessKeepsItsPrincipal() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var filter=new BureauAuditFilter(jdbc,new AuditClientAddress(""));
        var context=org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            context.setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken("test","anonymousUser",java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            var login=request("198.51.100.4",null,null);login.setRequestURI("/api/auth/login");
            filter.doFilter(login,new MockHttpServletResponse(),(req,res)->{
                req.setAttribute("ybcaseAuditUser","attempted-login-user");req.setAttribute(BureauAuditFilter.BIZ_CODE_ATTR,1001);
            });
            verify(jdbc).update(anyString(),eq("attempted-login-user"),eq("POST"),eq("/api/auth/login"),eq(1001),eq("198.51.100.4"),isNull());
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("actual-handler","unused",java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_HANDLER"))));
            var business=request("198.51.100.4",null,null);business.setAttribute("ybcaseAuditUser","must-not-replace-principal");
            filter.doFilter(business,new MockHttpServletResponse(),(req,res)->{});
            verify(jdbc).update(anyString(),eq("actual-handler"),eq("POST"),eq("/api/audit-address-test"),eq(200),eq("198.51.100.4"),isNull());
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

}
