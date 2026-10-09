package com.brainserve.appointment.iam.config;

import com.brainserve.appointment.iam.application.PrivilegedSecurityPolicy;
import com.brainserve.appointment.iam.domain.RefreshTokenSession;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.MfaCredentialRepository;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActiveAccountFilterTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RefreshTokenSessionRepository sessions = mock(RefreshTokenSessionRepository.class);
    private final MfaCredentialRepository credentials = mock(MfaCredentialRepository.class);
    private final ActiveAccountFilter filter = new ActiveAccountFilter(users, sessions, credentials,
            new PrivilegedSecurityPolicy("ROLE_SYSTEM_ADMIN,ROLE_CEO,ROLE_HR_ADMIN,ROLE_MANAGER,ROLE_TEAM_LEAD", 300),
            new ObjectMapper().findAndRegisterModules());
    private UserAccount user;
    private RefreshTokenSession session;
    private Set<String> authorities;

    @BeforeEach
    void setup() {
        user = new UserAccount("admin@brainserve.in", null, "hash", false, Set.of(SystemRole.ROLE_SYSTEM_ADMIN));
        ReflectionTestUtils.setField(user, "id", userId);
        session = new RefreshTokenSession(userId, "hash", familyId, Instant.now().plusSeconds(900));
        authorities = new LinkedHashSet<>();
        user.getRoles().forEach(role -> authorities.add(role.name()));
        user.effectivePermissions().forEach(permission -> authorities.add(permission.name()));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(sessions.findActiveFamily(eq(userId), eq(familyId), any())).thenAnswer(invocation ->
                session.isUsable() ? Optional.of(session) : Optional.empty());
        authenticate(familyId, null);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void restrictedTokensCanReachOnlyExactChallengeAndPasswordRoutes() throws Exception {
        assertResponse("GET", "/api/v1/dashboard/summary", 403, "MFA_REQUIRED");
        assertResponse("GET", "/api/v1/auth/security", 200, null);
        assertResponse("POST", "/api/auth/mfa/enrollment", 200, null);
        assertResponse("POST", "/api/v1/auth/mfa/enrollment/confirm", 200, null);
        assertResponse("GET", "/api/v1/auth/sessions", 403, "MFA_REQUIRED");
        assertResponse("GET", "/api/v1/admin/auth/security", 403, "MFA_REQUIRED");
        assertResponse("DELETE", "/api/v1/auth/security", 403, "MFA_REQUIRED");
    }

    @Test
    void accountRevocationRejectsTheAlreadyIssuedAccessTokenImmediately() throws Exception {
        verified(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        assertResponse("GET", "/api/v1/dashboard/summary", 200, null);
        session.revoke();
        assertResponse("GET", "/api/v1/dashboard/summary", 401, "SESSION_REVOKED");
    }

    @Test
    void oldTokensWithoutStableFamilyClaimRequireANewSignIn() throws Exception {
        authenticate(null, null);
        assertResponse("GET", "/api/v1/auth/security", 401, "INVALID_ACCESS_TOKEN");
        verifyNoInteractions(sessions);
    }

    @Test
    void verificationOfAnotherTokenDoesNotUpgradeAnUnverifiedJwt() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        session = new RefreshTokenSession(userId, "hash", familyId, now.plusSeconds(900), now, now);
        when(credentials.existsByUserIdAndEnrolledAtIsNotNull(userId)).thenReturn(true);
        assertResponse("GET", "/api/v1/dashboard/summary", 403, "MFA_REQUIRED");
    }

    @Test
    void recentVerificationIsRequiredForPermissionRecoveryAndExportActions() throws Exception {
        Instant old = Instant.now().minusSeconds(301).truncatedTo(ChronoUnit.SECONDS);
        verified(old);
        assertResponse("GET", "/api/v1/dashboard/summary", 200, null);
        assertResponse("PUT", "/api/v1/admin/users/" + UUID.randomUUID() + "/permissions", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/admin/account-recovery/" + UUID.randomUUID() + "/approve", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/admin/staff-accounts/" + UUID.randomUUID() + "/reset-password", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("GET", "/api/v1/report-exports/" + UUID.randomUUID() + "/download-url", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/integrations/connections", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/integrations/connections/" + UUID.randomUUID() + "/reconnect", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("GET", "/api/v1/integrations/connections", 200, null);
        assertResponse("GET", "/api/v1/integrations/google-calendar/config", 200, null);
        assertResponse("GET", "/api/v1/integrations/google-calendar/calendar.ics", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/integrations/google-calendar/consents", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/integrations/google-calendar/consents/" + UUID.randomUUID() + "/complete", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/integrations/connections/" + UUID.randomUUID() + "/reconcile", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/integrations/google-calendar/connections/" + UUID.randomUUID() + "/revocation/retry", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("POST", "/api/v1/support/diagnostics", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("GET", "/api/v1/support/diagnostics/" + UUID.randomUUID() + "/download", 403, "MFA_STEP_UP_REQUIRED");
        assertResponse("DELETE", "/api/v1/auth/sessions/" + familyId, 403, "MFA_STEP_UP_REQUIRED");
        verified(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        assertResponse("PUT", "/api/v1/admin/users/" + UUID.randomUUID() + "/permissions", 200, null);
        assertResponse("POST", "/api/v1/integrations/connections", 200, null);
        assertResponse("GET", "/api/v1/integrations/google-calendar/calendar.ics", 200, null);
        assertResponse("POST", "/api/v1/integrations/google-calendar/consents", 200, null);
        assertResponse("GET", "/api/v1/support/diagnostics/preview", 200, null);
    }

    @Test
    void databaseOutageIsAnAvailabilityErrorAndDoesNotRunTheEndpoint() throws Exception {
        when(users.findById(userId)).thenThrow(new DataAccessResourceFailureException("test DB outage"));
        assertResponse("GET", "/api/v1/auth/me", 503, "SECURITY_STATE_UNAVAILABLE");
    }

    @Test
    void encodedEndpointNamesCannotBypassFreshVerification() throws Exception {
        verified(Instant.now().minusSeconds(301).truncatedTo(ChronoUnit.SECONDS));
        for (String path : java.util.List.of("/api/v1/release-profile", "/api/v1/release%2Dprofile", "/api/v1/%72elease-profile")) {
            assertResponse("GET", path, 403, "MFA_STEP_UP_REQUIRED");
            assertResponse("PUT", path, 403, "MFA_STEP_UP_REQUIRED");
        }
        assertResponse("GET", "/api/v1/support/%64iagnostics/preview", 403, "MFA_STEP_UP_REQUIRED");
        verified(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        assertResponse("GET", "/api/v1/release%2Dprofile", 200, null);
    }

    @Test
    void forcedPasswordChangeCannotEnrollMfaUntilNewLogin() throws Exception {
        ReflectionTestUtils.setField(user, "forcePasswordChange", true);
        assertResponse("POST", "/api/v1/auth/change-password/request-otp", 200, null);
        assertResponse("POST", "/api/v1/auth/mfa/enrollment", 401, "PASSWORD_CHANGE_REQUIRED");
    }

    private void verified(Instant at) {
        session = new RefreshTokenSession(userId, "hash", familyId, Instant.now().plusSeconds(900), at, at);
        when(credentials.existsByUserIdAndEnrolledAtIsNotNull(userId)).thenReturn(true);
        authenticate(familyId, at);
    }
    private void authenticate(UUID family, Instant proof) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token").header("alg", "HS256")
                .subject(userId.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("authorities", authorities);
        if (family != null) builder.claim("sid", family.toString());
        if (proof != null) builder.claim("mfaVerifiedAt", proof.getEpochSecond());
        var token = new JwtAuthenticationToken(builder.build(), authorities.stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(token);
    }
    private void assertResponse(String method, String path, int status, String error) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
        assertThat(response.getStatus()).isEqualTo(status);
        if (error == null) assertThat(chain.getRequest()).isNotNull();
        else {
            assertThat(chain.getRequest()).isNull();
            assertThat(response.getContentAsString()).contains("\"errorCode\":\"" + error + "\"");
        }
    }
}
