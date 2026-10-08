package com.brainserve.appointment.iam.config;

import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.application.PrivilegedSecurityPolicy;
import com.brainserve.appointment.iam.application.PrivilegedSecurityService;
import com.brainserve.appointment.iam.infrastructure.MfaCredentialRepository;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
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
    private final RefreshTokenSessionRepository sessions;
    private final MfaCredentialRepository credentials;
    private final PrivilegedSecurityPolicy policy;
    private final ObjectMapper mapper;

    public ActiveAccountFilter(UserAccountRepository users, RefreshTokenSessionRepository sessions,
                               MfaCredentialRepository credentials, PrivilegedSecurityPolicy policy, ObjectMapper mapper) {
        this.users = users; this.sessions = sessions; this.credentials = credentials; this.policy = policy; this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            try {
                if (!authorize(request, response, jwt)) return;
            } catch (DataAccessException unavailable) {
                reject(response, HttpStatus.SERVICE_UNAVAILABLE, "SECURITY_STATE_UNAVAILABLE",
                        "Account security could not be checked. Try again shortly.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean authorize(HttpServletRequest request, HttpServletResponse response,
                              JwtAuthenticationToken jwt) throws IOException {
        UUID userId, familyId;
        try {
            userId = UUID.fromString(jwt.getToken().getSubject());
            familyId = UUID.fromString(jwt.getToken().getClaimAsString("sid"));
        } catch (RuntimeException invalid) {
            return reject(response, HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sign in again to establish a secure session.");
        }
        var account = users.findById(userId).filter(user -> user.isEnabled() && !user.isArchived()).orElse(null);
        if (account == null) return reject(response, HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_ACTIVE",
                "This account is disabled or archived. Sign-in access is no longer available.");
        var session = sessions.findActiveFamily(userId, familyId, Instant.now()).orElse(null);
        if (session == null) return reject(response, HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "This session expired or was signed out. Sign in again.");
        Set<String> currentAuthorities = new LinkedHashSet<>();
        account.getRoles().forEach(role -> currentAuthorities.add(role.name()));
        account.effectivePermissions().forEach(permission -> currentAuthorities.add(permission.name()));
        Set<String> tokenAuthorities = new LinkedHashSet<>();
        jwt.getAuthorities().forEach(authority -> tokenAuthorities.add(authority.getAuthority()));
        if (!currentAuthorities.equals(tokenAuthorities)) return reject(response, HttpStatus.UNAUTHORIZED,
                "ACCOUNT_AUTHORITY_CHANGED", "Your role or permissions changed. Sign in again to continue.");
        String path = canonicalPath(request);
        if (account.isForcePasswordChange() && !isPasswordChangePath(path, request.getMethod())) {
            return reject(response, HttpStatus.UNAUTHORIZED, "PASSWORD_CHANGE_REQUIRED", "Change the temporary password before accessing BrainServe Connect.");
        }
        boolean enrolled = credentials.existsByUserIdAndEnrolledAtIsNotNull(userId);
        boolean mfaRequired = policy.required(account, enrolled);
        Instant proof = PrivilegedSecurityService.effectiveProof(session, JwtService.mfaVerifiedAt(jwt.getToken()));
        if (mfaRequired && (!enrolled || proof == null) && !isChallengePath(path, request.getMethod())) {
            return reject(response, HttpStatus.FORBIDDEN, "MFA_REQUIRED", enrolled
                    ? "Verify your authenticator or a recovery code to continue."
                    : "Enroll an authenticator to secure this account before continuing.");
        }
        if (requiresFreshProof(path, request.getMethod()) && (!enrolled || proof == null
                || !proof.plus(policy.stepUpAge()).isAfter(Instant.now()))) {
            return reject(response, HttpStatus.FORBIDDEN, "MFA_STEP_UP_REQUIRED", "Verify your authenticator or a recovery code before this action.");
        }
        return true;
    }

    private String canonicalPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v1/")) return path.substring(7);
        if (path.startsWith("/api/")) return path.substring(4);
        return path;
    }
    private boolean isPasswordChangePath(String path, String method) {
        return ("GET".equals(method) && path.equals("/auth/me")) || ("POST".equals(method)
                && Set.of("/auth/logout", "/auth/logout-all", "/auth/change-password/request-otp", "/auth/change-password/confirm").contains(path));
    }
    private boolean isChallengePath(String path, String method) {
        return isPasswordChangePath(path, method)
                || ("GET".equals(method) && path.equals("/auth/security"))
                || ("POST".equals(method) && Set.of("/auth/mfa/enrollment", "/auth/mfa/enrollment/confirm", "/auth/mfa/verify").contains(path));
    }
    private boolean requiresFreshProof(String path, String method) {
        boolean write = Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
        return ("DELETE".equals(method) && path.startsWith("/auth/sessions/"))
                || (write && path.matches("/admin/users/[^/]+/permissions"))
                || (write && (path.startsWith("/admin/role-transitions") || path.startsWith("/admin/account-recovery")))
                || (write && path.matches("/admin/staff-accounts/[^/]+/(reset-password|email|status)"))
                || path.startsWith("/admin/account-closures/archived-recovery")
                || (write && path.startsWith("/admin/account-closures/direct-archive"))
                || (write && path.startsWith("/admin/kiosks"))
                || (write && path.startsWith("/integrations/"))
                || path.equals("/integrations/google-calendar/calendar.ics")
                || path.equals("/support/diagnostics") || path.startsWith("/support/diagnostics/")
                || path.equals("/report-exports") || path.startsWith("/report-exports/");
    }
    private boolean reject(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        if (status == HttpStatus.UNAUTHORIZED) SecurityContextHolder.clearContext();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status == HttpStatus.FORBIDDEN ? "Additional verification required" : "Authentication refresh required");
        problem.setProperty("errorCode", code);
        problem.setProperty("timestamp", Instant.now());
        mapper.writeValue(response.getOutputStream(), problem);
        return false;
    }
}
