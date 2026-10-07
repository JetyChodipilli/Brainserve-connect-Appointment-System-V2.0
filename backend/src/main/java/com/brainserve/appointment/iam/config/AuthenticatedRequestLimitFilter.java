package com.brainserve.appointment.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Applied after JWT and active-account checks; accounts never share an IP quota. */
@Component
public class AuthenticatedRequestLimitFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final int uploads;
    private final int exportJobs;
    private final int exports;
    private final int searches;
    private final int streamConnects;

    public AuthenticatedRequestLimitFilter(StringRedisTemplate redis, ObjectMapper mapper,
            @Value("${brainserve.security.rate-limit.uploads-per-minute:20}") int uploads,
            @Value("${brainserve.security.rate-limit.export-jobs-per-minute:5}") int exportJobs,
            @Value("${brainserve.security.rate-limit.exports-per-minute:30}") int exports,
            @Value("${brainserve.security.rate-limit.searches-per-minute:120}") int searches,
            @Value("${brainserve.security.rate-limit.stream-connects-per-minute:12}") int streamConnects) {
        this.redis = redis;
        this.mapper = mapper;
        this.uploads = RateLimitFilter.bounded(uploads, 10_000);
        this.exportJobs = RateLimitFilter.bounded(exportJobs, 10_000);
        this.exports = RateLimitFilter.bounded(exports, 10_000);
        this.searches = RateLimitFilter.bounded(searches, 10_000);
        this.streamConnects = RateLimitFilter.bounded(streamConnects, 10_000);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Rule rule = rule(request);
        if (rule == null || !(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        UUID accountId;
        try {
            accountId = UUID.fromString(jwt.getToken().getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            RateLimitFilter.writeProblem(mapper, response, 401, "INVALID_ACCESS_TOKEN", "The access token subject is invalid.");
            return;
        }
        String key = "rate:account:{" + accountId + "}:" + rule.name();
        if (RateLimitFilter.allow(redis, mapper, response, key, rule.limit(), 60)) chain.doFilter(request, response);
    }

    private Rule rule(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if ("POST".equals(method)) {
            if (path.equals("/api/v1/documents") || path.equals("/api/v1/profile/me/photo") || path.equals("/api/v1/bulk-imports/preview")) return new Rule("upload", uploads);
            if (path.equals("/api/v1/report-exports") || path.matches("/api/v1/report-exports/[^/]+/retry")
                    || path.equals("/api/v1/support/diagnostics"))
                return new Rule("export-job", exportJobs);
            if (path.startsWith("/api/v1/integrations/")) return new Rule("integration-write", exportJobs);
        }
        if ("GET".equals(method)) {
            if (path.matches("/api/v1/report-exports/[^/]+/download-url")
                    || path.matches("/api/v1/support/diagnostics/[^/]+/download")
                    || path.equals("/api/v1/integrations/google-calendar/calendar.ics")) return new Rule("export", exports);
            if (path.equals("/api/v1/integrations/google-calendar/config")
                    || path.equals("/api/v1/integrations/google-calendar/consents")
                    || path.matches("/api/v1/integrations/google-calendar/connections/[^/]+")
                    || path.matches("/api/v1/integrations/connections/[^/]+/reconciliation"))
                return new Rule("search", searches);
            if (path.equals("/api/v1/support/diagnostics/preview")) return new Rule("search", searches);
            if (path.equals("/api/v1/realtime/stream")) return new Rule("stream-connect", streamConnects);
            String query = request.getParameter("query");
            if (path.equals("/api/v1/history") || path.equals("/api/v1/visitors/search")
                    || (path.startsWith("/api/") && query != null && !query.isBlank())) return new Rule("search", searches);
        }
        return null;
    }

    private record Rule(String name, int limit) {}
}
