package com.brainserve.appointment.realtime.application;

import com.brainserve.appointment.iam.api.SessionAccess;
import com.brainserve.appointment.realtime.api.RealtimeUpdateController;
import com.brainserve.appointment.shared.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RealtimeUpdateHubTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SessionAccess sessions = mock(SessionAccess.class);
    private final RealtimeUpdateHub hub = spy(new RealtimeUpdateHub(redis, sessions, 3, 90));
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final UUID account = UUID.randomUUID();
    private final UUID family = UUID.randomUUID();

    @BeforeEach void ready() {
        when(sessions.isActive(any(), any(), nullable(Instant.class), anySet())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(0L);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);
        doReturn(emitter).when(hub).createEmitter(anyLong());
    }

    @Test
    void connectsUnderTheAccountLeaseAndChecksTheStableSessionProof() throws Exception {
        Instant proof = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        assertSame(emitter, hub.connect(jwt(account, family, Instant.now().plusSeconds(300), proof)));
        verify(redis).execute(any(RedisScript.class), eq(List.of(key(account))), anyString(), eq("3"), eq("90000"));
        verify(sessions).isActive(account, family, proof, Set.of("ROLE_EMPLOYEE"));
        verify(hub).createEmitter(longThat(timeout -> timeout > 0 && timeout <= 300_000));
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void quotaDenialExposesRetryAfterAndDoesNotCreateAnEmitter() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(67L);
        var response = new MockHttpServletResponse();
        var failure = assertThrows(RealtimeUpdateHub.AdmissionException.class,
                () -> new RealtimeUpdateController(hub).stream(jwt(), response));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, failure.getStatus());
        assertEquals("STREAM_LIMIT_EXCEEDED", failure.getErrorCode());
        assertEquals("67", response.getHeader("Retry-After"));
        verifyNoInteractions(emitter);
    }

    @Test
    void streamHttpErrorsRetainProblemJsonAndRetryHintsWithEventStreamAcceptHeader() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RealtimeUpdateController(hub))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(), List.of()));
        try {
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(67L);
            mvc.perform(get("/api/v1/realtime/stream").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "67"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errorCode").value("STREAM_LIMIT_EXCEEDED"));
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                    .thenThrow(new RedisConnectionFailureException("unavailable"));
            mvc.perform(get("/api/v1/realtime/stream").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Retry-After"))
                    .andExpect(jsonPath("$.errorCode").value("SECURITY_STATE_UNAVAILABLE"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void redisAndDatabaseAdmissionOutagesAre503AndNeverQuotaDenials() {
        for (RuntimeException failure : List.of(new RedisConnectionFailureException("unavailable"), new QueryTimeoutException("timeout"))) {
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenThrow(failure);
            assertUnavailable();
        }
        reset(redis);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(null);
        assertUnavailable(); // A missing script result also fails closed.
        when(sessions.isActive(any(), any(), nullable(Instant.class), anySet())).thenThrow(new QueryTimeoutException("database"));
        assertUnavailable();
        verifyNoInteractions(emitter);
    }

    @Test
    void completionTimeoutAndErrorEachReleaseExactlyOneLease() {
        hub.connect(jwt());
        var completion = ArgumentCaptor.forClass(Runnable.class);
        var timeout = ArgumentCaptor.forClass(Runnable.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onCompletion(completion.capture());
        verify(emitter).onTimeout(timeout.capture());
        verify(emitter).onError(error.capture());
        completion.getValue().run();
        timeout.getValue().run();
        error.getValue().accept(new IOException("closed"));
        verify(redis, times(1)).execute(any(RedisScript.class), eq(List.of(key(account))), anyString());
        hub.heartbeat();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
    }

    @Test
    void timeoutClosesTheEmitterAndReleasesItsLease() {
        hub.connect(jwt());
        var timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(timeout.capture());
        timeout.getValue().run();
        verify(emitter).complete();
        verify(redis).execute(any(RedisScript.class), eq(List.of(key(account))), anyString());
    }

    @Test
    void revokedSessionClosesAtHeartbeatAndCannotRenewItsLease() {
        hub.connect(jwt());
        when(sessions.isActive(any(), any(), nullable(Instant.class), anySet())).thenReturn(false);
        hub.heartbeat();
        verify(emitter).complete();
        verify(redis).execute(any(RedisScript.class), eq(List.of(key(account))), anyString());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
    }

    @Test
    void lostOrExpiredLeaseClosesInsteadOfSilentlyReacquiring() throws Exception {
        hub.connect(jwt());
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(0L);
        hub.broadcastRefresh();
        hub.heartbeat();
        verify(emitter).complete();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void outageClosesExistingStreamsAndReleaseFailureIsLeftToExpiry() {
        hub.connect(jwt());
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenThrow(new RedisConnectionFailureException("lost"));
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(new RedisConnectionFailureException("lost"));
        assertDoesNotThrow(hub::heartbeat);
        assertDoesNotThrow(hub::close);
        verify(emitter).complete();
    }

    @Test
    void sessionStoreOutageClosesExistingStream() {
        hub.connect(jwt());
        when(sessions.isActive(any(), any(), nullable(Instant.class), anySet())).thenThrow(new QueryTimeoutException("database"));
        hub.heartbeat();
        verify(emitter).complete();
    }

    @Test
    void disconnectedWritesReleaseWithoutDoubleDispatchingClientAbort() throws Exception {
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        hub.connect(jwt());
        verify(redis).execute(any(RedisScript.class), eq(List.of(key(account))), anyString());
        verify(emitter, never()).completeWithError(any());
        hub.heartbeat();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
    }

    @Test
    void expiredOrMissingSessionTokenNeverAcquiresALease() {
        Jwt expired = jwt(account, family, Instant.now().minusSeconds(1), null);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(RealtimeUpdateHub.AdmissionException.class, () -> hub.connect(expired)).getStatus());
        Jwt missing = Jwt.withTokenValue("test").header("alg", "HS256").subject(account.toString())
                .claim("authorities", List.of("ROLE_EMPLOYEE")).expiresAt(Instant.now().plusSeconds(300)).build();
        assertThrows(RealtimeUpdateHub.AdmissionException.class, () -> hub.connect(missing));
        verifyNoInteractions(redis);
        verifyNoInteractions(sessions);
    }

    @Test
    void shutdownReleasesLocalConnections() {
        hub.connect(jwt());
        hub.close();
        hub.close();
        verify(emitter).complete();
        verify(redis, times(1)).execute(any(RedisScript.class), eq(List.of(key(account))), anyString());
    }

    private void assertUnavailable() {
        var failure = assertThrows(RealtimeUpdateHub.AdmissionException.class, () -> hub.connect(jwt()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertEquals("SECURITY_STATE_UNAVAILABLE", failure.getErrorCode());
        assertEquals(0, failure.retryAfterSeconds());
    }

    private Jwt jwt() { return jwt(account, family, Instant.now().plusSeconds(300), null); }
    static Jwt jwt(UUID account, UUID family, Instant expiresAt, Instant proof) {
        var builder = Jwt.withTokenValue("test").header("alg", "HS256").subject(account.toString())
                .claim("sid", family.toString()).claim("authorities", List.of("ROLE_EMPLOYEE"))
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiresAt);
        if (proof != null) builder.claim("mfaVerifiedAt", proof.getEpochSecond());
        return builder.build();
    }

    private String key(UUID id) { return "realtime:streams:{" + id + "}"; }
}
