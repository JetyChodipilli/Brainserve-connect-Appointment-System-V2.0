package com.brainserve.appointment.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthenticatedRequestLimitFilterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AuthenticatedRequestLimitFilter filter = new AuthenticatedRequestLimitFilter(redis,
            new ObjectMapper().findAndRegisterModules(), 20, 5, 30, 120, 12);

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @CsvSource({"POST,/api/v1/documents,upload,20", "POST,/api/v1/profile/me/photo,upload,20",
            "POST,/api/v1/report-exports,export-job,5", "POST,/api/v1/report-exports/REF/retry,export-job,5",
            "GET,/api/v1/report-exports/REF/download-url,export,30", "GET,/api/v1/history,search,120",
            "GET,/api/v1/visitors/search,search,120", "GET,/api/v1/realtime/stream,stream-connect,12"})
    void expensiveRoutesHaveExplicitAccountBudgets(String method, String path, String operation, int limit) throws Exception {
        UUID account = UUID.randomUUID();
        authenticate(account.toString());
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(23L);
        var response = run(method, path);
        assertEquals(429, response.getStatus());
        assertEquals("23", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
        verify(redis).execute(any(RedisScript.class), eq(List.of("rate:account:{" + account + "}:" + operation)),
                eq("60"), eq(Integer.toString(limit)));
    }

    @Test
    void coworkersAndOperationsRemainIndependentButNewTokensCannotResetAccountBudget() throws Exception {
        Map<String, Long> counters = new HashMap<>();
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenAnswer(call -> {
            List<String> keys = call.getArgument(1);
            return counters.merge(keys.getFirst(), 1L, Long::sum) > Integer.parseInt(call.getArgument(3)) ? 60L : 0L;
        });
        UUID firstAccount = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            authenticate(firstAccount.toString());
            assertEquals(204, run("POST", "/api/v1/report-exports").getStatus());
        }
        assertEquals(429, run("POST", "/api/v1/report-exports/REF/retry").getStatus());
        assertEquals(204, run("GET", "/api/v1/report-exports/REF/download-url").getStatus());
        authenticate(UUID.randomUUID().toString());
        assertEquals(204, run("POST", "/api/v1/report-exports").getStatus());
    }

    @Test
    void outageIs503NotQuotaExhaustion() throws Exception {
        authenticate(UUID.randomUUID().toString());
        for (RuntimeException failure : List.of(new RedisConnectionFailureException("unavailable"), new QueryTimeoutException("timeout"))) {
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenThrow(failure);
            var response = run("GET", "/api/v1/realtime/stream");
            assertEquals(503, response.getStatus());
            assertTrue(response.getContentAsString().contains("SECURITY_STATE_UNAVAILABLE"));
            assertNull(response.getHeader("Retry-After"));
            reset(redis);
        }
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(null);
        assertEquals(503, run("GET", "/api/v1/history").getStatus());
    }

    @Test
    void anonymousAndRoutineReadsDoNotSpendAccountBudgets() throws Exception {
        assertEquals(204, run("GET", "/api/v1/realtime/stream").getStatus());
        authenticate(UUID.randomUUID().toString());
        assertEquals(204, run("OPTIONS", "/api/v1/report-exports").getStatus());
        assertEquals(204, run("GET", "/api/v1/report-exports").getStatus());
        assertEquals(204, run("GET", "/api/v1/notifications/inbox").getStatus());
        assertEquals(204, run("GET", "/api/v1/employees").getStatus());
        verifyNoInteractions(redis);
    }

    @Test
    void keywordSearchesOnListEndpointsUseTheAccountSearchBudget() throws Exception {
        UUID account = UUID.randomUUID();
        authenticate(account.toString());
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(19L);
        var request = new MockHttpServletRequest("GET", "/api/v1/employees");
        request.addParameter("query", "Anita");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("exhausted search quota must not execute the query"));
        assertEquals(429, response.getStatus());
        verify(redis).execute(any(RedisScript.class), eq(List.of("rate:account:{" + account + "}:search")), eq("60"), eq("120"));
    }

    @Test
    void invalidSubjectCannotCreateArbitraryRedisKeys() throws Exception {
        authenticate("bad:{key}");
        assertEquals(401, run("GET", "/api/v1/history").getStatus());
        verifyNoInteractions(redis);
    }

    @Test
    void downstreamErrorsRemainBusinessErrors() {
        authenticate(UUID.randomUUID().toString());
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(0L);
        assertThrows(QueryTimeoutException.class, () -> filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/history"),
                new MockHttpServletResponse(), (req, res) -> { throw new QueryTimeoutException("business query"); }));
    }

    private void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue(UUID.randomUUID().toString()).header("alg", "HS256").subject(subject).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private MockHttpServletResponse run(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.0.2.8");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(204));
        return response;
    }
}
