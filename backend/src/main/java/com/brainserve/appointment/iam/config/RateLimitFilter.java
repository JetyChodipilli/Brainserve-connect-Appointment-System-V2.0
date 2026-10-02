package com.brainserve.appointment.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); local ttl=redis.call('TTL',KEYS[1]); "
                    + "if n==1 or ttl<0 then redis.call('EXPIRE',KEYS[1],ARGV[1]); ttl=tonumber(ARGV[1]); end; "
                    + "if n>tonumber(ARGV[2]) then return math.max(1,ttl); end; return 0;", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ClientAddressResolver addresses;
    private final int loginRequests;
    private final int refreshRequests;
    private final int logoutRequests;
    private final int authWindowSeconds;

    public RateLimitFilter(StringRedisTemplate redis, ObjectMapper mapper, ClientAddressResolver addresses,
                           @Value("${brainserve.security.rate-limit.login-requests:600}") int loginRequests,
                           @Value("${brainserve.security.rate-limit.refresh-requests:1200}") int refreshRequests,
                           @Value("${brainserve.security.rate-limit.logout-requests:600}") int logoutRequests,
                           @Value("${brainserve.security.rate-limit.auth-window-seconds:60}") int authWindowSeconds) {
        this.redis = redis;
        this.mapper = mapper;
        this.addresses = addresses;
        this.loginRequests = bounded(loginRequests, 100_000);
        this.refreshRequests = bounded(refreshRequests, 100_000);
        this.logoutRequests = bounded(logoutRequests, 100_000);
        this.authWindowSeconds = bounded(authWindowSeconds, 3600);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = rule(request);
        if (rule == null) { chain.doFilter(request, response); return; }
        String key = "rate:ip:" + addresses.resolve(request) + ":" + rule.key();
        if (allow(redis, mapper, response, key, rule.limit(), rule.windowSeconds())) chain.doFilter(request, response);
    }

    static boolean allow(StringRedisTemplate redis, ObjectMapper mapper, HttpServletResponse response,
                         String key, int limit, int windowSeconds) throws IOException {
        Long retryAfter;
        try {
            retryAfter = redis.execute(SCRIPT, List.of(key), Integer.toString(windowSeconds), Integer.toString(limit));
        } catch (DataAccessException ex) {
            writeProblem(mapper, response, 503, "SECURITY_STATE_UNAVAILABLE", "The request cannot be verified at this time");
            return false;
        }
        if (retryAfter == null || retryAfter < 0) {
            writeProblem(mapper, response, 503, "SECURITY_STATE_UNAVAILABLE", "The request cannot be verified at this time");
            return false;
        }
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            writeProblem(mapper, response, 429, "RATE_LIMIT_EXCEEDED", "Too many requests. Please try again later.");
            return false;
        }
        return true;
    }

    private Rule rule(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) && !"DELETE".equals(request.getMethod())) return null;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Account failures are still tracked by AuthenticationService. This
        // broad network budget lets coworkers behind one NAT sign in normally.
        if (path.equals("/api/v1/auth/login") || path.equals("/api/auth/login"))
            return new Rule("login", loginRequests, authWindowSeconds);
        if (path.equals("/api/v1/auth/refresh") || path.equals("/api/auth/refresh"))
            return new Rule("token-refresh", refreshRequests, authWindowSeconds);
        if (path.equals("/api/v1/auth/logout") || path.equals("/api/auth/logout"))
            return new Rule("logout", logoutRequests, authWindowSeconds);
        if (path.equals("/api/v1/auth/change-password/request-otp") || path.equals("/api/auth/change-password/request-otp"))
            return new Rule("password-change-request", 5, 3600);
        if (path.equals("/api/v1/auth/change-password/confirm") || path.equals("/api/auth/change-password/confirm"))
            return new Rule("password-change-confirm", 10, 600);
        if (path.equals("/api/register") || path.equals("/api/v1/register"))
            return new Rule("account-registration", 10, 3600);
        if (path.matches("/api/v1/public/appointments/[^/]+/verify-otp")) return new Rule("otp", 10, 600);
        if (path.equals("/api/v1/public/appointments")) return new Rule("public-appointment", 20, 3600);
        if (path.equals("/api/v1/public/visitors")) return new Rule("public-visitor", 20, 3600);
        if (path.matches("/api/v1/public/appointments/[^/]+/cancel/request-otp"))
            return new Rule("public-appointment-cancel-otp", 5, 900);
        if (path.matches("/api/v1/public/appointments/[^/]+/cancel"))
            return new Rule("public-appointment-cancel", 10, 900);
        if (path.equals("/api/v1/auth/recovery/requests") || path.equals("/api/auth/recovery/requests"))
            return new Rule("account-recovery-request", 5, 3600);
        if (path.equals("/api/v1/auth/recovery/password") || path.equals("/api/auth/recovery/password")
                || path.equals("/api/v1/auth/recovery/email") || path.equals("/api/auth/recovery/email"))
            return new Rule("account-recovery-use", 10, 900);
        return null;
    }

    static void writeProblem(ObjectMapper mapper, HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.valueOf(status), detail);
        problem.setProperty("errorCode", code); problem.setProperty("timestamp", Instant.now());
        mapper.writeValue(response.getOutputStream(), problem);
    }

    static int bounded(int value, int maximum) {
        if (value < 1 || value > maximum) throw new IllegalArgumentException("Rate limit configuration is outside its supported range");
        return value;
    }

    private record Rule(String key, int limit, int windowSeconds) {}
}
