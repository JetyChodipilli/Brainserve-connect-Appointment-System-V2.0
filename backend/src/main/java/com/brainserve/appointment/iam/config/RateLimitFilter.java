package com.brainserve.appointment.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.RedisConnectionFailureException;
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
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n;", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RateLimitFilter(StringRedisTemplate redis, ObjectMapper mapper) { this.redis = redis; this.mapper = mapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = rule(request);
        if (rule == null) { chain.doFilter(request, response); return; }
        try {
            String key = "rate:" + request.getRemoteAddr() + ":" + rule.key();
            Long count = redis.execute(SCRIPT, List.of(key), Integer.toString(rule.windowSeconds()));
            if (count != null && count > rule.limit()) {
                response.setHeader("Retry-After", Integer.toString(rule.windowSeconds()));
                writeProblem(response, 429, "RATE_LIMIT_EXCEEDED", "Too many requests. Please try again later.");
                return;
            }
            chain.doFilter(request, response);
        } catch (RedisConnectionFailureException ex) {
            writeProblem(response, 503, "SECURITY_STATE_UNAVAILABLE", "The request cannot be verified at this time");
        }
    }

    private Rule rule(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) && !"DELETE".equals(request.getMethod())) return null;
        String path = request.getRequestURI();
        if (path.equals("/api/v1/auth/login") || path.equals("/api/auth/login")) return new Rule("login", 10, 900);
        if (path.equals("/api/v1/auth/refresh") || path.equals("/api/auth/refresh"))
            return new Rule("token-refresh", 120, 60);
        if (path.equals("/api/v1/auth/logout") || path.equals("/api/auth/logout"))
            return new Rule("logout", 60, 60);
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

    private void writeProblem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.valueOf(status), detail);
        problem.setProperty("errorCode", code); problem.setProperty("timestamp", Instant.now());
        mapper.writeValue(response.getOutputStream(), problem);
    }

    private record Rule(String key, int limit, int windowSeconds) {}
}
