package com.brainserve.appointment.realtime.application;

import com.brainserve.appointment.iam.api.SessionAccess;
import com.brainserve.appointment.iam.config.AuthenticatedRequestLimitFilter;
import com.brainserve.appointment.iam.config.ClientAddressResolver;
import com.brainserve.appointment.iam.config.RateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production Lua scripts against Redis, including cross-instance races. */
@Testcontainers(disabledWithoutDocker = true)
class RedisRatePolicyIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private RealtimeUpdateHub first;
    private RealtimeUpdateHub second;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID account = UUID.randomUUID();

    @BeforeEach void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
        SessionAccess sessions = (user, family, proof, authorities) -> true;
        first = new RealtimeUpdateHub(redis, sessions, 3, 90);
        second = new RealtimeUpdateHub(redis, sessions, 3, 90);
    }

    @AfterEach void disconnect() {
        SecurityContextHolder.clearContext();
        if (first != null) first.close();
        if (second != null) second.close();
        if (connection != null) connection.destroy();
    }

    @Test
    void concurrentInstancesShareOneAtomicAccountQuotaAndReleaseOnShutdown() throws Exception {
        List<Future<Boolean>> attempts = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 16; i++) {
                RealtimeUpdateHub hub = i % 2 == 0 ? first : second;
                attempts.add(workers.submit(() -> {
                    try {
                        hub.connect(token(account));
                        return true;
                    } catch (RealtimeUpdateHub.AdmissionException denial) {
                        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denial.getStatus());
                        assertTrue(denial.retryAfterSeconds() > 0 && denial.retryAfterSeconds() <= 90);
                        return false;
                    }
                }));
            }
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get()) accepted++;
            assertEquals(3, accepted);
        }
        assertEquals(3L, redis.opsForZSet().zCard(key(account)));
        assertTrue(redis.getExpire(key(account)) > 0);
        assertDoesNotThrow(() -> first.connect(token(UUID.randomUUID())));
        first.close();
        second.close();
        assertEquals(0L, redis.opsForZSet().zCard(key(account)));
        assertDoesNotThrow(() -> second.connect(token(account)));
    }

    @Test
    void expiredLeaseCannotBeResurrectedByAnOldInstance() {
        first.connect(token(account));
        String oldLease = redis.opsForZSet().range(key(account), 0, -1).iterator().next();
        redis.opsForZSet().add(key(account), oldLease, 0);
        second.connect(token(account));
        first.heartbeat();
        assertNull(redis.opsForZSet().score(key(account), oldLease));
        assertEquals(1L, redis.opsForZSet().zCard(key(account)));
        second.heartbeat();
        assertEquals(1L, redis.opsForZSet().zCard(key(account)));
    }

    @Test
    void crashedInstanceSlotsExpireWithoutCleanupCallbacks() {
        for (int i = 0; i < 3; i++) first.connect(token(account));
        assertThrows(RealtimeUpdateHub.AdmissionException.class, () -> second.connect(token(account)));
        // Shorten the server-side TTL to simulate a crashed node's lease period.
        redis.expire(key(account), Duration.ofMillis(5));
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (Boolean.TRUE.equals(redis.hasKey(key(account)))) Thread.sleep(5);
        });
        assertDoesNotThrow(() -> second.connect(token(account)));
        first.heartbeat();
        assertEquals(1L, redis.opsForZSet().zCard(key(account)));
    }

    @Test
    void realCounterUsesRemainingTtlAndSpoofingCannotBypassIpBudget() throws Exception {
        var limiter = new RateLimitFilter(redis, mapper, new ClientAddressResolver(""), 3, 1200, 600, 60);
        for (int i = 0; i < 3; i++) assertEquals(204, login(limiter, "198.51.100.1", "203.0.113." + i).getStatus());
        redis.expire("rate:ip:198.51.100.1:login", Duration.ofSeconds(20));
        var rejected = login(limiter, "198.51.100.1", "203.0.113.99");
        assertEquals(429, rejected.getStatus());
        assertTrue(Integer.parseInt(rejected.getHeader("Retry-After")) <= 20);
        assertEquals(204, login(limiter, "198.51.100.2", "203.0.113.99").getStatus());
        assertTrue(redis.getExpire("rate:ip:198.51.100.1:login") <= 20, "denials must not extend the window");
    }

    @Test
    void authenticatedExportJobsAreScopedToAccountAndExpireAutomatically() throws Exception {
        var limiter = new AuthenticatedRequestLimitFilter(redis, mapper, 20, 2, 30, 120, 12);
        authenticate(account);
        assertEquals(204, export(limiter).getStatus());
        assertEquals(204, export(limiter).getStatus());
        assertEquals(429, export(limiter).getStatus());
        authenticate(UUID.randomUUID());
        assertEquals(204, export(limiter).getStatus());
        String key = "rate:account:{" + account + "}:export-job";
        assertTrue(redis.getExpire(key) > 0);
        redis.expire(key, Duration.ofMillis(5));
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (Boolean.TRUE.equals(redis.hasKey(key))) Thread.sleep(5);
        });
        authenticate(account);
        assertEquals(204, export(limiter).getStatus());
    }

    private MockHttpServletResponse login(RateLimitFilter filter, String peer, String forged) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forged);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(204));
        return response;
    }

    private MockHttpServletResponse export(AuthenticatedRequestLimitFilter filter) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/report-exports"), response,
                (req, res) -> response.setStatus(204));
        return response;
    }

    private void authenticate(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token(id), List.of()));
    }
    private org.springframework.security.oauth2.jwt.Jwt token(UUID id) {
        return RealtimeUpdateHubTest.jwt(id, UUID.randomUUID(), Instant.now().plusSeconds(300), null);
    }
    private String key(UUID id) { return "realtime:streams:{" + id + "}"; }
}
