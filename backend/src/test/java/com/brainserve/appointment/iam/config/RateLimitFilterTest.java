package com.brainserve.appointment.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RateLimitFilter filter = new RateLimitFilter(redis, new ObjectMapper().findAndRegisterModules(),
            new ClientAddressResolver(""), 600, 1200, 600, 60);

    @Test
    void officeNatAdmitsSixHundredLoginsButRetainsABroadAbuseBudget() throws Exception {
        Map<String, Long> counters = new HashMap<>();
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenAnswer(call -> {
            List<String> keys = call.getArgument(1);
            long count = counters.merge(keys.getFirst(), 1L, Long::sum);
            return count > Integer.parseInt(call.getArgument(3)) ? 42L : 0L;
        });
        AtomicInteger admitted = new AtomicInteger();
        for (int attempt = 0; attempt < 600; attempt++) {
            assertEquals(200, run("POST", "/api/v1/auth/login", "192.0.2.1", admitted).getStatus());
        }
        var rejected = run("POST", "/api/v1/auth/login", "192.0.2.1", admitted);
        assertEquals(429, rejected.getStatus());
        assertEquals("42", rejected.getHeader("Retry-After"));
        assertTrue(rejected.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
        assertEquals(200, run("POST", "/api/v1/auth/login", "192.0.2.2", admitted).getStatus());
        assertEquals(601, admitted.get());
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/auth/refresh,1200,60", "/api/v1/auth/recovery/requests,5,3600",
            "/api/v1/public/appointments,20,3600", "/api/v1/public/visitors,20,3600",
            "/api/v1/public/appointments/REF/verify-otp,10,600",
            "/api/v1/public/appointments/REF/cancel/request-otp,5,900",
            "/api/v1/public/appointments/REF/cancel,10,900"})
    void protectedPublicOperationsKeepTheirExplicitBudgets(String path, int limit, int window) throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(17L);
        AtomicInteger admitted = new AtomicInteger();
        var response = run("POST", path, "192.0.2.1", admitted);
        assertEquals(429, response.getStatus());
        assertEquals("17", response.getHeader("Retry-After"));
        assertEquals(0, admitted.get());
        verify(redis).execute(any(RedisScript.class), anyList(), eq(Integer.toString(window)), eq(Integer.toString(limit)));
    }

    @Test
    void securityStateFailuresAndMissingCountersFailClosed() throws Exception {
        for (RuntimeException failure : List.of(new RedisConnectionFailureException("Unavailable"),
                new QueryTimeoutException("Timeout"))) {
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenThrow(failure);
            assertUnavailable();
            reset(redis);
        }
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(null);
        assertUnavailable();
    }

    @ParameterizedTest
    @CsvSource({"authorize,google-calendar-authorize", "callback,google-calendar-callback"})
    void oauthRedirectsAreBoundedBeforeAuthenticationAndFailClosed(String route, String key) throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        String path = "/api/v1/integrations/google-calendar/" + route;
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(17L);
        var rejected = run("GET", path, "192.0.2.8", admitted);
        assertEquals(429, rejected.getStatus());
        assertEquals("17", rejected.getHeader("Retry-After"));
        assertEquals("no-store", rejected.getHeader("Cache-Control"));
        assertEquals(0, admitted.get());
        verify(redis).execute(any(RedisScript.class), eq(List.of("rate:ip:192.0.2.8:" + key)), eq("60"), eq("30"));
        reset(redis);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        var unavailable = run("GET", path, "192.0.2.8", admitted);
        assertEquals(503, unavailable.getStatus());
        assertEquals("no-store", unavailable.getHeader("Cache-Control"));
        assertEquals(0, admitted.get());
    }

    @Test
    void readsAndPreflightDoNotSpendTheLoginBudget() throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        run("GET", "/api/v1/public/company-profile", "192.0.2.1", admitted);
        run("OPTIONS", "/api/v1/auth/login", "192.0.2.1", admitted);
        assertEquals(2, admitted.get());
        verifyNoInteractions(redis);
    }

    @Test
    void downstreamDataErrorsAreNotMisreportedAsLimiterFailures() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(0L);
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        var response = new MockHttpServletResponse();
        assertThrows(QueryTimeoutException.class, () -> filter.doFilter(request, response,
                (req, res) -> { throw new QueryTimeoutException("business query timed out"); }));
        assertEquals(200, response.getStatus());
    }

    @Test
    void clientSuppliedForwardedHeaderCannotChooseTheCounterKey() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(0L);
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.8");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        verify(redis).execute(any(RedisScript.class), eq(List.of("rate:ip:192.0.2.8:login")), eq("60"), eq("600"));
    }

    @Test
    void invalidBudgetsFailAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitFilter(redis, new ObjectMapper(),
                new ClientAddressResolver(""), 0, 1200, 600, 60));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitFilter(redis, new ObjectMapper(),
                new ClientAddressResolver(""), 600, 1200, 600, 3601));
    }

    private void assertUnavailable() throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        var response = run("POST", "/api/v1/auth/login", "192.0.2.1", admitted);
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("SECURITY_STATE_UNAVAILABLE"));
        assertEquals(0, admitted.get());
    }

    private MockHttpServletResponse run(String method, String path, String address, AtomicInteger admitted) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> admitted.incrementAndGet());
        return response;
    }
}
