package com.brainserve.appointment.realtime.application;

import com.brainserve.appointment.iam.api.SessionAccess;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RealtimeUpdateHub {
    private static final Logger log = LoggerFactory.getLogger(RealtimeUpdateHub.class);
    private static final long EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    // Redis time avoids application clock skew; expired members are removed
    // atomically with admission. A crashed instance cannot retain a quota slot.
    private static final String LEASE_CLOCK = "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); "
            + "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now); ";
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(LEASE_CLOCK
            + "if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then "
            + "local first=redis.call('ZRANGE',KEYS[1],0,0,'WITHSCORES'); "
            + "return math.max(1,math.ceil((tonumber(first[2])-now)/1000)); end; "
            + "redis.call('ZADD',KEYS[1],now+tonumber(ARGV[3]),ARGV[1]); "
            + "redis.call('PEXPIRE',KEYS[1],ARGV[3]); return 0;", Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(LEASE_CLOCK
            + "if not redis.call('ZSCORE',KEYS[1],ARGV[1]) then return 0; end; "
            + "redis.call('ZADD',KEYS[1],now+tonumber(ARGV[2]),ARGV[1]); "
            + "redis.call('PEXPIRE',KEYS[1],ARGV[2]); return 1;", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "return redis.call('ZREM',KEYS[1],ARGV[1]);", Long.class);

    private final StringRedisTemplate redis;
    private final SessionAccess sessions;
    private final int maxStreams;
    private final long leaseMillis;
    private final Map<UUID, Connection> emitters = new ConcurrentHashMap<>();

    public RealtimeUpdateHub(StringRedisTemplate redis, SessionAccess sessions,
            @Value("${brainserve.realtime.max-streams-per-account:3}") int maxStreams,
            @Value("${brainserve.realtime.stream-lease-seconds:90}") int leaseSeconds) {
        if (maxStreams < 1 || maxStreams > 20 || leaseSeconds < 75 || leaseSeconds > 600) {
            throw new IllegalArgumentException("Invalid realtime stream quota or lease duration");
        }
        this.redis = redis;
        this.sessions = sessions;
        this.maxStreams = maxStreams;
        this.leaseMillis = leaseSeconds * 1000L;
    }

    public SseEmitter connect(Jwt jwt) {
        UUID accountId;
        UUID familyId;
        Instant proof;
        Set<String> authorities;
        try {
            accountId = UUID.fromString(jwt.getSubject());
            familyId = UUID.fromString(jwt.getClaimAsString("sid"));
            Number verifiedAt = jwt.getClaim("mfaVerifiedAt");
            proof = verifiedAt == null ? null : Instant.ofEpochSecond(verifiedAt.longValue());
            authorities = Set.copyOf(jwt.getClaimAsStringList("authorities"));
            if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now())) throw inactive();
        } catch (IllegalArgumentException | NullPointerException | ClassCastException exception) {
            throw inactive();
        }
        Long retryAfter;
        UUID connectionId = UUID.randomUUID();
        try {
            if (!sessions.isActive(accountId, familyId, proof, authorities)) throw inactive();
            retryAfter = redis.execute(ACQUIRE, List.of(key(accountId)), connectionId.toString(),
                    Integer.toString(maxStreams), Long.toString(leaseMillis));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
        if (retryAfter == null || retryAfter < 0) throw unavailable();
        if (retryAfter > 0) throw new AdmissionException("STREAM_LIMIT_EXCEEDED",
                "Close an existing live connection and try again.", HttpStatus.TOO_MANY_REQUESTS, retryAfter);

        long timeout = Math.min(EMITTER_TIMEOUT_MILLIS, Math.max(1L, jwt.getExpiresAt().toEpochMilli() - System.currentTimeMillis()));
        SseEmitter emitter = createEmitter(timeout);
        Connection connection = new Connection(emitter, accountId, familyId, jwt.getExpiresAt(), proof, authorities);
        emitters.put(connectionId, connection);
        emitter.onCompletion(() -> drop(connectionId, false));
        emitter.onTimeout(() -> drop(connectionId, true));
        emitter.onError(ignored -> drop(connectionId, false));
        send(connectionId, connection, "connected", "ready", false);
        return emitter;
    }

    public void broadcastRefresh() {
        emitters.forEach((id, connection) -> send(id, connection, "workspace-refresh", "refresh", true));
    }

    @Scheduled(fixedDelay = 25_000L)
    public void heartbeat() {
        String timestamp = Instant.now().toString();
        emitters.forEach((id, connection) -> {
            try {
                if (!sessions.isActive(connection.accountId(), connection.familyId(), connection.proof(), connection.authorities())) {
                    drop(id, true);
                    return;
                }
                send(id, connection, "heartbeat", timestamp, true);
            } catch (DataAccessException exception) {
                drop(id, true);
            }
        });
    }

    private void send(UUID id, Connection connection, String eventName, String data, boolean renewLease) {
        try {
            if (!connection.expiresAt().isAfter(Instant.now())) {
                drop(id, true);
                return;
            }
            if (renewLease) {
                Long renewed = redis.execute(RENEW, List.of(key(connection.accountId())), id.toString(), Long.toString(leaseMillis));
                if (!Long.valueOf(1).equals(renewed)) {
                    drop(id, true);
                    return;
                }
            }
            connection.emitter().send(SseEmitter.event().name(eventName).data(data));
        } catch (DataAccessException exception) {
            drop(id, true);
        } catch (IOException | IllegalStateException exception) {
            // A client abort already triggers async error handling in Tomcat.
            // Releasing its lease must not dispatch a second server failure.
            drop(id, false);
        }
    }

    private void drop(UUID id, boolean complete) {
        Connection removed = emitters.remove(id);
        if (removed == null) return;
        try {
            redis.execute(RELEASE, List.of(key(removed.accountId())), id.toString());
        } catch (DataAccessException exception) {
            log.debug("Stream lease release deferred to expiry while security state is unavailable");
        } finally {
            if (complete) removed.emitter().complete();
        }
    }

    @PreDestroy
    public void close() { emitters.keySet().forEach(id -> drop(id, true)); }

    SseEmitter createEmitter(long timeout) { return new SseEmitter(timeout); }

    private static String key(UUID accountId) { return "realtime:streams:{" + accountId + "}"; }
    private static AdmissionException unavailable() {
        return new AdmissionException("SECURITY_STATE_UNAVAILABLE", "Live connection security state is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, 0);
    }
    private static AdmissionException inactive() {
        return new AdmissionException("INVALID_ACCESS_TOKEN", "Sign in again to open a live connection.", HttpStatus.UNAUTHORIZED, 0);
    }

    public static class AdmissionException extends BusinessException {
        private final long retryAfterSeconds;
        AdmissionException(String code, String detail, HttpStatus status, long retryAfterSeconds) {
            super(code, detail, status);
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private record Connection(SseEmitter emitter, UUID accountId, UUID familyId, Instant expiresAt,
                              Instant proof, Set<String> authorities) {}
}
