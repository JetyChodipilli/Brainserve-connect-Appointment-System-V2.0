package com.brainserve.appointment.iam.config;

import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class ActiveAccountFilter extends OncePerRequestFilter {
    private final UserAccountRepository users;
    private final ObjectMapper mapper;

    public ActiveAccountFilter(UserAccountRepository users, ObjectMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            UUID userId;
            try { userId = UUID.fromString(jwt.getToken().getSubject()); }
            catch (RuntimeException ex) {
                reject(response, "INVALID_ACCESS_TOKEN", "The access token subject is invalid.");
                return;
            }
            var activeAccount = users.findById(userId)
                    .filter(account -> account.isEnabled() && !account.isArchived())
                    .orElse(null);
            if (activeAccount == null) {
                SecurityContextHolder.clearContext();
                reject(response, "ACCOUNT_NOT_ACTIVE",
                        "This account is disabled or archived. Sign-in access is no longer available.");
                return;
            }
            Set<String> currentAuthorities = new LinkedHashSet<>();
            activeAccount.getRoles().forEach(role -> currentAuthorities.add(role.name()));
            activeAccount.effectivePermissions().forEach(permission -> currentAuthorities.add(permission.name()));
            Set<String> tokenAuthorities = new LinkedHashSet<>();
            jwt.getAuthorities().forEach(authority -> tokenAuthorities.add(authority.getAuthority()));
            if (!currentAuthorities.equals(tokenAuthorities)) {
                SecurityContextHolder.clearContext();
                reject(response, "ACCOUNT_AUTHORITY_CHANGED",
                        "Your role or permissions changed. Sign in again to continue.");
                return;
            }
            if (activeAccount.isForcePasswordChange() && !isPasswordChangePath(request.getRequestURI())) {
                SecurityContextHolder.clearContext();
                reject(response, "PASSWORD_CHANGE_REQUIRED",
                        "Change the temporary password before accessing BrainServe Connect.");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isPasswordChangePath(String path) {
        return path.endsWith("/auth/me")
                || path.endsWith("/auth/logout")
                || path.endsWith("/auth/logout-all")
                || path.endsWith("/auth/change-password/request-otp")
                || path.endsWith("/auth/change-password/confirm");
    }

    private void reject(HttpServletResponse response, String code, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Authentication refresh required");
        problem.setProperty("errorCode", code);
        problem.setProperty("timestamp", Instant.now());
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
