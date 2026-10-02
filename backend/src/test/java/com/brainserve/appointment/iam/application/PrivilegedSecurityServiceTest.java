package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.domain.MfaCredential;
import com.brainserve.appointment.iam.domain.RefreshTokenSession;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.MfaCredentialRepository;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrivilegedSecurityServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RefreshTokenSessionRepository sessions = mock(RefreshTokenSessionRepository.class);
    private final MfaCredentialRepository credentials = mock(MfaCredentialRepository.class);
    private final TotpService totp = new TotpService();
    private final AuthenticationService authentication = mock(AuthenticationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final PrivilegedSecurityPolicy policy = new PrivilegedSecurityPolicy(
            "ROLE_SYSTEM_ADMIN,ROLE_CEO,ROLE_HR_ADMIN,ROLE_MANAGER,ROLE_TEAM_LEAD", 300);
    private final PrivilegedSecurityService service = new PrivilegedSecurityService(users, sessions, credentials, totp, authentication, policy, audit);
    private UserAccount user;
    private MfaCredential credential;
    private RefreshTokenSession session;

    @BeforeEach
    void setup() {
        user = new UserAccount("admin@brainserve.in", null, "hash", false, Set.of(SystemRole.ROLE_SYSTEM_ADMIN));
        ReflectionTestUtils.setField(user, "id", userId);
        credential = new MfaCredential(userId);
        session = new RefreshTokenSession(userId, "hash", familyId, Instant.now().plusSeconds(900));
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(credentials.findById(userId)).thenReturn(Optional.of(credential));
        when(sessions.findActiveFamily(eq(userId), eq(familyId), any())).thenAnswer(invocation ->
                session.isUsable() ? Optional.of(session) : Optional.empty());
        when(authentication.completeMfa(eq(user), any(), any())).thenAnswer(invocation -> {
            Instant proof = invocation.getArgument(2);
            session = new RefreshTokenSession(userId, "next-hash", familyId, Instant.now().plusSeconds(86400),
                    session.getSessionStartedAt(), proof);
            return new AuthenticationService.TokenPair("verified-token", Instant.now().plusSeconds(600), "refresh-token", false, false, true);
        });
    }

    @Test
    void enrollmentShowsCodesOnceRejectsReplayAndSingleUseRecoveryCodeCannotBeReused() {
        var enrollment = service.beginEnrollment(userId, familyId, null);
        var verified = service.confirmEnrollment(userId, familyId, null, totp.codeAt(enrollment.secret(), Instant.now()));
        assertThat(credential.isEnrolled()).isTrue();
        assertThat(verified.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        assertThat(verified.recoveryCodes()).allMatch(value -> value.matches("[A-F0-9]{8}(-[A-F0-9]{8}){3}"));
        long acceptedStep = credential.getLastAcceptedStep();
        String usedCode = totp.codeAt(enrollment.secret(), Instant.ofEpochSecond(acceptedStep * 30));
        assertCode(() -> service.verify(userId, familyId, usedCode), "INVALID_MFA_CODE");
        var recovered = service.verify(userId, familyId, verified.recoveryCodes().getFirst());
        assertThat(recovered.recoveryCodes()).isEmpty();
        assertThat(credential.recoveryCodesRemaining()).isEqualTo(9);
        assertCode(() -> service.verify(userId, familyId, verified.recoveryCodes().getFirst()), "INVALID_MFA_CODE");
        assertThat(credential.recoveryCodesRemaining()).isEqualTo(9);
        verify(audit).record(eq("MFA_RECOVERY_CODE_USED"), eq("USER_ACCOUNT"), eq(userId.toString()), anyString());
    }

    @Test
    void proofFailureBudgetSurvivesEnrollmentRestartsAndSuccessfulCodesCannotBypassTheLock() {
        var enrollment = service.beginEnrollment(userId, familyId, null);
        for (int index = 0; index < 4; index++) assertCode(() -> service.confirmEnrollment(userId, familyId, null, "invalid"), "INVALID_MFA_CODE");
        assertCode(() -> service.confirmEnrollment(userId, familyId, null, "invalid"), "MFA_RATE_LIMITED");
        assertCode(() -> service.beginEnrollment(userId, familyId, null), "MFA_RATE_LIMITED");
        assertCode(() -> service.confirmEnrollment(userId, familyId, null, totp.codeAt(enrollment.secret(), Instant.now())), "MFA_RATE_LIMITED");
        verifyNoInteractions(authentication);
        ReflectionTestUtils.setField(credential, "failureWindowAt", Instant.now().minusSeconds(901));
        assertThat(service.confirmEnrollment(userId, familyId, null, totp.codeAt(enrollment.secret(), Instant.now())).tokens().mfaRequired()).isFalse();
    }

    @Test
    void enrollmentMustBelongToThisFamilyAndMustStillBeUnexpired() {
        credential.beginEnrollment(totp.newSecret(), UUID.randomUUID(), Instant.now().plusSeconds(100));
        assertCode(() -> service.confirmEnrollment(userId, familyId, null, "123456"), "MFA_ENROLLMENT_EXPIRED");
        credential.beginEnrollment(totp.newSecret(), familyId, Instant.now().minusSeconds(1));
        assertCode(() -> service.confirmEnrollment(userId, familyId, null, "123456"), "MFA_ENROLLMENT_EXPIRED");
        session.revoke();
        assertCode(() -> service.beginEnrollment(userId, familyId, null), "SESSION_REVOKED");
        verifyNoInteractions(authentication);
    }

    @Test
    void missingStaleOrDifferentOwnersSessionCanNeverBeRevoked() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        session = new RefreshTokenSession(userId, "hash", familyId, now.plusSeconds(900), now, now);
        UUID anotherFamily = UUID.randomUUID();
        when(sessions.findActiveFamily(eq(userId), eq(anotherFamily), any())).thenReturn(Optional.empty());
        assertCode(() -> service.revoke(userId, familyId, null, anotherFamily), "MFA_STEP_UP_REQUIRED");
        assertCode(() -> service.revoke(userId, familyId, now.minusSeconds(301), anotherFamily), "MFA_STEP_UP_REQUIRED");
        assertCode(() -> service.revoke(userId, familyId, now, anotherFamily), "SESSION_NOT_FOUND");
        verify(sessions, never()).revokeFamily(any(), any());
    }

    @Test
    void refreshOrAnotherTabsNewerProofCannotMakeAnOlderJwtFresh() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        session = new RefreshTokenSession(userId, "hash", familyId, now.plusSeconds(900), now, now);
        assertThat(PrivilegedSecurityService.effectiveProof(session, null)).isNull();
        assertThat(PrivilegedSecurityService.effectiveProof(session, now.minusSeconds(600))).isEqualTo(now.minusSeconds(600));
        assertThat(PrivilegedSecurityService.effectiveProof(session, now.plusSeconds(1))).isNull();
    }

    @Test
    void replacementRequiresFreshProofAndDoesNotChangeExistingSecretsOrCodesDuringSetup() {
        var initial = service.beginEnrollment(userId, familyId, null);
        service.confirmEnrollment(userId, familyId, null, totp.codeAt(initial.secret(), Instant.now()));
        assertCode(() -> service.beginEnrollment(userId, familyId, null), "MFA_STEP_UP_REQUIRED");
        Instant proof = session.getMfaVerifiedAt();
        var pending = service.beginEnrollment(userId, familyId, proof);
        assertThat(pending.secret()).isNotEqualTo(initial.secret());
        assertThat(credential.getSecret()).isEqualTo(initial.secret());
        assertThat(credential.recoveryCodesRemaining()).isEqualTo(10);
        assertCode(() -> service.confirmEnrollment(userId, familyId, proof.minusSeconds(301),
                totp.codeAt(pending.secret(), Instant.now())), "MFA_STEP_UP_REQUIRED");
    }

    @Test
    void optionalEnrollmentBecomesMandatoryAtTheNextLogin() {
        UserAccount employee = new UserAccount("employee@brainserve.in", UUID.randomUUID(), "hash", false, Set.of(SystemRole.ROLE_EMPLOYEE));
        assertThat(policy.required(employee, false)).isFalse();
        assertThat(policy.required(employee, true)).isTrue();
        for (SystemRole role : Set.of(SystemRole.ROLE_SYSTEM_ADMIN, SystemRole.ROLE_CEO, SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_MANAGER, SystemRole.ROLE_TEAM_LEAD)) {
            assertThat(policy.required(new UserAccount("user@brainserve.in", null, "hash", false, Set.of(role)), false)).isTrue();
        }
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String expected) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
