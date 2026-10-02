package com.brainserve.appointment.iam.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ClientAddressResolverTest {
    @Test
    void defaultDistrustsAllForwardingHeaders() {
        var request = request("192.0.2.8", "203.0.113.99");
        request.addHeader("Forwarded", "for=203.0.113.98");
        request.addHeader("X-Real-IP", "203.0.113.97");
        assertEquals("192.0.2.8", new ClientAddressResolver("").resolve(request));
    }

    @Test
    void trustedProxyChainStopsAtFirstUntrustedHopAndIgnoresSpoofedPrefix() {
        var resolver = new ClientAddressResolver("10.1.0.0/16, 192.0.2.8/32");
        assertEquals("198.51.100.21", resolver.resolve(request("10.1.1.8", "203.0.113.99, 198.51.100.21, 192.0.2.8")));
        assertEquals("198.51.100.21", resolver.resolve(request("10.1.1.8", "garbage, 198.51.100.21, 192.0.2.8")));
        assertEquals("10.2.1.8", resolver.resolve(request("10.2.1.8", "198.51.100.21")));
    }

    @Test
    void repeatedHeadersAreOneOrderedChain() {
        var request = request("10.1.1.8", "203.0.113.99");
        request.addHeader("X-Forwarded-For", "198.51.100.21, 10.1.2.8");
        assertEquals("198.51.100.21", new ClientAddressResolver("10.1.0.0/16").resolve(request));
    }

    @Test
    void ipv6AndMappedIpv4AreCanonicalized() {
        var resolver = new ClientAddressResolver("2001:db8:1::/48");
        assertEquals("2001:db8:2:0:0:0:0:1", resolver.resolve(request("2001:db8:1::8", "2001:db8:2::1")));
        assertEquals("192.0.2.8", new ClientAddressResolver("").resolve(request("::ffff:192.0.2.8", "203.0.113.99")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "localhost", "127.1", "012.0.0.1", "[2001:db8::1]", "fe80::1%eth0", "192.0.2.1:80", "", "198.51.100.1, "})
    void malformedTrustedHopFallsBackToSocketPeer(String forwarded) {
        assertEquals("10.1.1.8", new ClientAddressResolver("10.1.0.0/16").resolve(request("10.1.1.8", forwarded)));
    }

    @Test
    void oversizedAndExcessiveHopListsCannotConsumeUnboundedWork() {
        var resolver = new ClientAddressResolver("10.1.0.0/16");
        assertEquals("10.1.1.8", resolver.resolve(request("10.1.1.8", "1".repeat(4097))));
        assertEquals("10.1.1.8", resolver.resolve(request("10.1.1.8", "10.1.1.1,".repeat(33) + "198.51.100.21")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost/8", "192.0.2.1/33", "2001:db8::1/129", "192.0.2.1/-1", "192.0.2.1/24/1"})
    void invalidAllowlistFailsAtStartup(String cidrs) {
        assertThrows(IllegalArgumentException.class, () -> new ClientAddressResolver(cidrs));
    }

    private MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
}
