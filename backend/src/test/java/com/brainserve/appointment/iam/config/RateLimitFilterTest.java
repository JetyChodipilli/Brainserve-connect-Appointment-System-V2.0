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
    private final RateLimitFilter filter = new RateLimitFilter(redis, new ObjectMapper().findAndRegisterModules());

    @Test
    void eleventhLoginIsRejectedButAnotherAddressKeepsItsOwnBudget() throws Exception {
        Map<String, Long> counters = new HashMap<>();
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenAnswer(call -> {
            List<String> keys = call.getArgument(1);
            return counters.merge(keys.getFirst(), 1L, Long::sum);
        });
        AtomicInteger admitted = new AtomicInteger();
        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals(200, run("POST", "/api/v1/auth/login", "192.0.2.1", admitted).getStatus());
        }
        var rejected = run("POST", "/api/v1/auth/login", "192.0.2.1", admitted);
        assertEquals(429, rejected.getStatus());
        assertEquals("900", rejected.getHeader("Retry-After"));
        assertTrue(rejected.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
        assertEquals(200, run("POST", "/api/v1/auth/login", "192.0.2.2", admitted).getStatus());
        assertEquals(11, admitted.get());
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/auth/refresh,121", "/api/v1/auth/recovery/requests,6",
            "/api/v1/public/appointments,21", "/api/v1/public/visitors,21",
            "/api/v1/public/appointments/REF/verify-otp,11",
            "/api/v1/public/appointments/REF/cancel/request-otp,6",
            "/api/v1/public/appointments/REF/cancel,11"})
    void protectedPublicOperationsRejectRequestsOverTheirLimit(String path, long count) throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(count);
        AtomicInteger admitted = new AtomicInteger();
        assertEquals(429, run("POST", path, "192.0.2.1", admitted).getStatus());
        assertEquals(0, admitted.get());
    }

    @Test
    void securityStateFailuresAndMissingCountersFailClosed() throws Exception {
        for (RuntimeException failure : List.of(new RedisConnectionFailureException("Unavailable"),
                new QueryTimeoutException("Timeout"))) {
            when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(failure);
            assertUnavailable();
            reset(redis);
        }
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(null);
        assertUnavailable();
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
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        var response = new MockHttpServletResponse();
        assertThrows(QueryTimeoutException.class, () -> filter.doFilter(request, response,
                (req, res) -> { throw new QueryTimeoutException("business query timed out"); }));
        assertEquals(200, response.getStatus());
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
