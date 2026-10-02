package com.brainserve.appointment.iam;

import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.application.AuthenticationService;
import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.application.PrivilegedSecurityService;
import com.brainserve.appointment.iam.application.TotpService;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.iam.infrastructure.MfaCredentialRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses real PostgreSQL transactions/locks and real JWTs; CI must run these with Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false",
        "brainserve.bootstrap.ceo-enabled=false",
        "spring.task.scheduling.enabled=false",
        "aws.s3.access-key=test-access-key", "aws.s3.secret-key=test-secret-key"
})
@AutoConfigureMockMvc
class PrivilegedSecurityIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MfaCredentialRepository credentials;
    @Autowired PasswordEncoder passwords;
    @Autowired AuthenticationService authentication;
    @Autowired PrivilegedSecurityService security;
    @Autowired TotpService totp;
    @Autowired JwtDecoder decoder;
    @Autowired JwtService jwtService;
    @MockitoBean EmailService emails;
    private static final String PASSWORD = "Integration!Pass2026";

    @Test
    void restrictedLoginEncryptionRotationAndImmediateLogoutWorkTogether() throws Exception {
        UserAccount user = user();
        var restricted = authentication.login(user.getEmail(), PASSWORD);
        assertThat(restricted.mfaRequired()).isTrue();
        assertThat(restricted.mfaEnrolled()).isFalse();
        mvc.perform(get("/api/v1/admin/permissions").header("Authorization", bearer(restricted.accessToken())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_REQUIRED"));
        var enrollment = security.beginEnrollment(user.getId(), family(restricted), null);
        var complete = security.confirmEnrollment(user.getId(), family(restricted), null, totp.codeAt(enrollment.secret(), Instant.now()));
        String ciphertext = jdbc.queryForObject("select secret_ciphertext from iam_mfa_credential where user_id=?", String.class, user.getId());
        assertThat(ciphertext).isNotBlank().doesNotContain(enrollment.secret());
        List<String> hashes = jdbc.queryForList("select code_hash from iam_mfa_recovery_code where user_id=?", String.class, user.getId());
        assertThat(hashes).hasSize(10).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
        assertThat(hashes).doesNotContainAnyElementsOf(complete.recoveryCodes());
        mvc.perform(get("/api/v1/admin/permissions").header("Authorization", bearer(restricted.accessToken())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_REQUIRED"));
        var refreshed = authentication.refresh(complete.tokens().refreshToken());
        assertThat(family(refreshed)).isEqualTo(family(complete.tokens()));
        assertThat(proof(refreshed)).isEqualTo(proof(complete.tokens()));
        mvc.perform(get("/api/v1/admin/permissions").header("Authorization", bearer(refreshed.accessToken())))
                .andExpect(status().isOk());
        authentication.logout(refreshed.refreshToken());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(complete.tokens().accessToken())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("SESSION_REVOKED"));
    }

    @Test
    void concurrentRecoveryConsumptionAllowsExactlyOneSession() throws Exception {
        var enrolled = enrolled();
        var first = authentication.login(enrolled.user().getEmail(), PASSWORD);
        var second = authentication.login(enrolled.user().getEmail(), PASSWORD);
        String recovery = enrolled.verification().recoveryCodes().getFirst();
        var outcomes = concurrent(
                () -> security.verify(enrolled.user().getId(), family(first), recovery),
                () -> security.verify(enrolled.user().getId(), family(second), recovery));
        assertOneVerifiedOneRejected(outcomes);
        assertThat(jdbc.queryForObject("select count(*) from iam_mfa_recovery_code where user_id=?", Integer.class, enrolled.user().getId())).isEqualTo(9);
        assertCode(() -> security.verify(enrolled.user().getId(), family(first), recovery), "INVALID_MFA_CODE");
    }

    @Test
    void concurrentTotpReplayIsRejectedAcrossDistinctSessionFamilies() throws Exception {
        var enrolled = enrolled();
        var first = authentication.login(enrolled.user().getEmail(), PASSWORD);
        var second = authentication.login(enrolled.user().getEmail(), PASSWORD);
        Long consumed = jdbc.queryForObject("select last_accepted_step from iam_mfa_credential where user_id=?", Long.class, enrolled.user().getId());
        String next = totp.codeAt(enrolled.secret(), Instant.ofEpochSecond((consumed + 1) * 30));
        var outcomes = concurrent(
                () -> security.verify(enrolled.user().getId(), family(first), next),
                () -> security.verify(enrolled.user().getId(), family(second), next));
        assertOneVerifiedOneRejected(outcomes);
    }

    @Test
    void concurrentRefreshAndRevocationCannotLeaveASurvivingSuccessor() throws Exception {
        var enrolled = enrolled();
        var tokens = enrolled.verification().tokens();
        concurrent(() -> authentication.refresh(tokens.refreshToken()), () -> {
            security.revoke(enrolled.user().getId(), family(tokens), proof(tokens), family(tokens));
            return "revoked";
        });
        assertThat(jdbc.queryForObject("select count(*) from iam_refresh_token_session where family_id=? and revoked_at is null", Integer.class, family(tokens))).isZero();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(tokens.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerScopedListAndRevocationAreAuditedAndCannotReachAnotherAccount() throws Exception {
        var first = enrolled();
        var second = enrolled();
        var token = first.verification().tokens();
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", bearer(token.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].familyId").value(family(token).toString()));
        mvc.perform(delete("/api/v1/auth/sessions/" + family(second.verification().tokens()))
                        .header("Authorization", bearer(token.accessToken())))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from iam_refresh_token_session where family_id=? and revoked_at is null", Integer.class,
                family(second.verification().tokens()))).isEqualTo(1);
        mvc.perform(delete("/api/v1/auth/sessions/" + family(token)).header("Authorization", bearer(token.accessToken())))
                .andExpect(status().isNoContent());
        List<String> events = jdbc.queryForList("select event_type from audit_event where target_id=?", String.class, first.user().getId().toString());
        assertThat(events).contains("SESSION_LIST_VIEWED", "SESSION_REVOKED");
    }

    @Test
    void invalidAttemptsCommitAndLockOutEvenACorrectRecoveryCode() {
        var enrolled = enrolled();
        var challenge = authentication.login(enrolled.user().getEmail(), PASSWORD);
        for (int index = 0; index < 4; index++) assertCode(() -> security.verify(enrolled.user().getId(), family(challenge), "invalid"), "INVALID_MFA_CODE");
        assertCode(() -> security.verify(enrolled.user().getId(), family(challenge), "invalid"), "MFA_RATE_LIMITED");
        assertThat(jdbc.queryForObject("select failed_attempts from iam_mfa_credential where user_id=?", Integer.class, enrolled.user().getId())).isEqualTo(5);
        assertCode(() -> security.verify(enrolled.user().getId(), family(challenge), enrolled.verification().recoveryCodes().getFirst()), "MFA_RATE_LIMITED");
        assertThat(jdbc.queryForObject("select count(*) from iam_mfa_recovery_code where user_id=?", Integer.class, enrolled.user().getId())).isEqualTo(10);
    }

    @Test
    void enrollmentAndStepUpExpireWithoutRefreshExtendingTheProof() throws Exception {
        UserAccount user = user();
        var challenge = authentication.login(user.getEmail(), PASSWORD);
        var enrollment = security.beginEnrollment(user.getId(), family(challenge), null);
        jdbc.update("update iam_mfa_credential set pending_expires_at=? where user_id=?", Timestamp.from(Instant.now().minusSeconds(1)), user.getId());
        assertCode(() -> security.confirmEnrollment(user.getId(), family(challenge), null, totp.codeAt(enrollment.secret(), Instant.now())), "MFA_ENROLLMENT_EXPIRED");
        var enrolled = enrolled();
        var tokens = enrolled.verification().tokens();
        Instant expiredProof = Instant.now().minusSeconds(301).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("update iam_refresh_token_session set mfa_verified_at=? where family_id=?", Timestamp.from(expiredProof), family(tokens));
        var oldAccess = jwtService.issue(enrolled.user(), family(tokens), expiredProof, true, true);
        mvc.perform(get("/api/v1/auth/security").header("Authorization", bearer(oldAccess.value())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfaVerified").value(true)).andExpect(jsonPath("$.stepUpRequired").value(true));
        var rotated = authentication.refresh(tokens.refreshToken());
        assertThat(proof(rotated)).isEqualTo(expiredProof);
        mvc.perform(post("/api/v1/report-exports").header("Authorization", bearer(rotated.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_STEP_UP_REQUIRED"));
        jdbc.update("update iam_refresh_token_session set expires_at=? where family_id=?", Timestamp.from(Instant.now().minusSeconds(1)), family(rotated));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(rotated.accessToken())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("SESSION_REVOKED"));
    }

    @Test
    void authenticatorReplacementKeepsOldCodesUntilConfirmThenRevokesEveryOldFamily() throws Exception {
        var enrolled = enrolled();
        var original = enrolled.verification().tokens();
        var replacement = security.beginEnrollment(enrolled.user().getId(), family(original), proof(original));
        assertThat(credentials.findById(enrolled.user().getId()).orElseThrow().getSecret()).isEqualTo(enrolled.secret());
        var second = authentication.login(enrolled.user().getEmail(), PASSWORD);
        var recovered = security.verify(enrolled.user().getId(), family(second), enrolled.verification().recoveryCodes().getFirst());
        assertThat(recovered.tokens().mfaRequired()).isFalse();
        var newAuthenticator = security.confirmEnrollment(enrolled.user().getId(), family(original), proof(original), totp.codeAt(replacement.secret(), Instant.now()));
        assertThat(family(newAuthenticator.tokens())).isNotEqualTo(family(original));
        assertThat(newAuthenticator.recoveryCodes()).doesNotContainAnyElementsOf(enrolled.verification().recoveryCodes());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(recovered.tokens().accessToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(original.accessToken())))
                .andExpect(status().isUnauthorized());
        var challenge = authentication.login(enrolled.user().getEmail(), PASSWORD);
        assertCode(() -> security.verify(enrolled.user().getId(), family(challenge), enrolled.verification().recoveryCodes().get(1)), "INVALID_MFA_CODE");
        assertThat(security.verify(enrolled.user().getId(), family(challenge), newAuthenticator.recoveryCodes().getFirst()).tokens().mfaRequired()).isFalse();
    }

    private UserAccount user() {
        return users.saveAndFlush(new UserAccount("security-" + UUID.randomUUID() + "@brainserve.in", null,
                passwords.encode(PASSWORD), false, Set.of(SystemRole.ROLE_SYSTEM_ADMIN)));
    }
    private Enrolled enrolled() {
        UserAccount user = user();
        var challenge = authentication.login(user.getEmail(), PASSWORD);
        var enrollment = security.beginEnrollment(user.getId(), family(challenge), null);
        return new Enrolled(user, enrollment.secret(), security.confirmEnrollment(user.getId(), family(challenge), null,
                totp.codeAt(enrollment.secret(), Instant.now())));
    }
    private UUID family(AuthenticationService.TokenPair tokens) { return UUID.fromString(decoder.decode(tokens.accessToken()).getClaimAsString("sid")); }
    private Instant proof(AuthenticationService.TokenPair tokens) { return JwtService.mfaVerifiedAt(decoder.decode(tokens.accessToken())); }
    private String bearer(String value) { return "Bearer " + value; }
    private List<Object> concurrent(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> race(first, ready, start));
            var b = pool.submit(() -> race(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
    private Object race(Callable<Object> action, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency start timed out");
        try { return action.call(); } catch (BusinessException expected) { return expected; }
    }
    private void assertOneVerifiedOneRejected(List<Object> outcomes) {
        assertThat(outcomes.stream().filter(PrivilegedSecurityService.Verification.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast)
                .map(BusinessException::getErrorCode).toList()).containsExactly("INVALID_MFA_CODE");
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String expected) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
    private record Enrolled(UserAccount user, String secret, PrivilegedSecurityService.Verification verification) {}
}
