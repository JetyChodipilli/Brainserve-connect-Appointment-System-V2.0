package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.SessionAccess;
import com.brainserve.appointment.iam.domain.MfaCredential;
import com.brainserve.appointment.iam.domain.RefreshTokenSession;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.MfaCredentialRepository;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PrivilegedSecurityService implements SessionAccess {
    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository sessions;
    private final MfaCredentialRepository credentials;
    private final TotpService totp;
    private final AuthenticationService authentication;
    private final PrivilegedSecurityPolicy policy;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public PrivilegedSecurityService(UserAccountRepository users, RefreshTokenSessionRepository sessions,
                                     MfaCredentialRepository credentials, TotpService totp,
                                     AuthenticationService authentication, PrivilegedSecurityPolicy policy, AuditService audit) {
        this.users = users; this.sessions = sessions; this.credentials = credentials; this.totp = totp;
        this.authentication = authentication; this.policy = policy; this.audit = audit;
    }

    @Transactional
    public Enrollment beginEnrollment(UUID userId, UUID familyId, Instant tokenVerifiedAt) {
        requireUserForUpdate(userId);
        RefreshTokenSession session = requireSession(userId, familyId);
        MfaCredential credential = credentials.findById(userId).orElseGet(() -> credentials.save(new MfaCredential(userId)));
        if (credential.isEnrolled()) requireFreshProof(session, tokenVerifiedAt);
        requireAttemptsAvailable(credential, Instant.now());
        UserAccount user = users.findById(userId).orElseThrow(this::invalidSession);
        if (!credential.pendingFor(familyId, Instant.now())) {
            credential.beginEnrollment(totp.newSecret(), familyId, Instant.now().plus(PrivilegedSecurityPolicy.ENROLLMENT_LIFETIME));
            audit.record("MFA_ENROLLMENT_STARTED", "USER_ACCOUNT", userId.toString(), "{}");
        }
        return new Enrollment(credential.getPendingSecret(), totp.enrollmentUri(user.getEmail(), credential.getPendingSecret()));
    }

    // Only rejected proofs commit the attempt counter. Any storage/audit error rolls back proof consumption and token issuance.
    @Transactional(noRollbackFor = SecurityCodeRejected.class)
    public Verification confirmEnrollment(UUID userId, UUID familyId, Instant tokenVerifiedAt, String code) {
        UserAccount user = requireUserForUpdate(userId);
        RefreshTokenSession session = requireSession(userId, familyId);
        MfaCredential credential = credentials.findById(userId).orElseThrow(() ->
                problem("MFA_ENROLLMENT_REQUIRED", "Start authenticator enrollment first", HttpStatus.FORBIDDEN));
        boolean replacing = credential.isEnrolled();
        if (replacing) requireFreshProof(session, tokenVerifiedAt);
        Instant now = Instant.now();
        requireAttemptsAvailable(credential, now);
        if (!credential.pendingFor(familyId, now)) {
            throw problem("MFA_ENROLLMENT_EXPIRED", "Start authenticator enrollment again", HttpStatus.FORBIDDEN);
        }
        var accepted = totp.matchingStep(credential.getPendingSecret(), code, now, null);
        if (accepted.isEmpty()) throw rejected(credential, now);
        List<String> recoveryCodes = newRecoveryCodes();
        credential.confirmEnrollment(now, accepted.getAsLong(), recoveryCodes.stream()
                .map(value -> recoveryHash(userId, value)).collect(Collectors.toSet()));
        Instant verifiedAt = now.truncatedTo(ChronoUnit.SECONDS);
        var tokens = replacing ? authentication.replaceMfa(user, verifiedAt) : authentication.completeMfa(user, session, verifiedAt);
        audit.record(replacing ? "MFA_AUTHENTICATOR_REPLACED" : "MFA_ENROLLED", "USER_ACCOUNT", userId.toString(), "{\"recoveryCodeCount\":10}");
        return new Verification(tokens, recoveryCodes);
    }

    @Transactional(noRollbackFor = SecurityCodeRejected.class)
    public Verification verify(UUID userId, UUID familyId, String code) {
        UserAccount user = requireUserForUpdate(userId);
        RefreshTokenSession session = requireSession(userId, familyId);
        MfaCredential credential = credentials.findById(userId).filter(MfaCredential::isEnrolled).orElseThrow(() ->
                problem("MFA_ENROLLMENT_REQUIRED", "Enroll an authenticator before continuing", HttpStatus.FORBIDDEN));
        Instant now = Instant.now();
        requireAttemptsAvailable(credential, now);
        var accepted = totp.matchingStep(credential.getSecret(), code, now, credential.getLastAcceptedStep());
        boolean recovered = false;
        if (accepted.isPresent()) credential.acceptStep(accepted.getAsLong());
        else if (isRecoveryCode(code)) recovered = credential.consumeRecoveryHash(recoveryHash(userId, code));
        if (accepted.isEmpty() && !recovered) throw rejected(credential, now);
        var tokens = authentication.completeMfa(user, session, now.truncatedTo(ChronoUnit.SECONDS));
        audit.record(recovered ? "MFA_RECOVERY_CODE_USED" : "MFA_VERIFIED", "USER_ACCOUNT", userId.toString(),
                "{\"familyId\":\"" + familyId + "\"}");
        return new Verification(tokens, List.of());
    }

    @Transactional(readOnly = true)
    public SecurityState state(UUID userId, UUID familyId, Instant tokenVerifiedAt) {
        UserAccount user = requireUser(userId);
        RefreshTokenSession session = requireSession(userId, familyId);
        MfaCredential credential = credentials.findById(userId).orElse(null);
        boolean enrolled = credential != null && credential.isEnrolled();
        Instant verifiedAt = effectiveProof(session, tokenVerifiedAt);
        boolean verified = enrolled && verifiedAt != null;
        Instant stepUpExpiry = verified ? verifiedAt.plus(policy.stepUpAge()) : null;
        return new SecurityState(policy.required(user, enrolled), enrolled, verified, verified ? verifiedAt : null,
                stepUpExpiry == null || !stepUpExpiry.isAfter(Instant.now()), stepUpExpiry,
                credential == null ? 0 : credential.recoveryCodesRemaining(), familyId, policy.privilegedRoles());
    }

    @Transactional
    public SessionList list(UUID userId, UUID currentFamily, Instant tokenVerifiedAt, int page) {
        UserAccount user = requireUser(userId);
        RefreshTokenSession current = requireSession(userId, currentFamily);
        boolean enrolled = credentials.existsByUserIdAndEnrolledAtIsNotNull(userId);
        if (policy.required(user, enrolled) && (!enrolled || effectiveProof(current, tokenVerifiedAt) == null)) {
            throw problem("MFA_REQUIRED", "Verify your authenticator before viewing sessions", HttpStatus.FORBIDDEN);
        }
        if (page < 0 || page > 1000) throw problem("INVALID_PAGE", "Choose a valid session page", HttpStatus.BAD_REQUEST);
        List<RefreshTokenSession> active = sessions.findActiveForUser(userId, Instant.now(), PageRequest.of(page, 50));
        List<SessionView> views = active.stream().map(session -> new SessionView(session.getFamilyId(),
                session.getSessionStartedAt(), session.getCreatedAt(), session.getExpiresAt(),
                session.getMfaVerifiedAt(), session.getFamilyId().equals(currentFamily))).toList();
        audit.record("SESSION_LIST_VIEWED", "USER_ACCOUNT", userId.toString(), "{\"page\":" + page + "}");
        return new SessionList(views, 50, page, active.size() == 50);
    }

    @Transactional
    public void revoke(UUID userId, UUID currentFamily, Instant tokenVerifiedAt, UUID targetFamily) {
        requireUserForUpdate(userId);
        RefreshTokenSession current = requireSession(userId, currentFamily);
        requireFreshProof(current, tokenVerifiedAt);
        // Owner is part of the lookup; a guessed family id cannot enumerate or revoke another account.
        sessions.findActiveFamily(userId, targetFamily, Instant.now()).orElseThrow(() ->
                problem("SESSION_NOT_FOUND", "The active session was not found", HttpStatus.NOT_FOUND));
        sessions.revokeFamily(targetFamily, Instant.now());
        audit.record("SESSION_REVOKED", "USER_ACCOUNT", userId.toString(),
                "{\"familyId\":\"" + targetFamily + "\"}");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID userId, UUID familyId, Instant tokenVerifiedAt, Set<String> authorities) {
        if (userId == null || familyId == null || authorities == null) return false;
        UserAccount user = users.findById(userId).orElse(null);
        if (user == null || !user.isEnabled() || user.isArchived() || user.isForcePasswordChange()) return false;
        Set<String> currentAuthorities = new LinkedHashSet<>();
        user.getRoles().forEach(role -> currentAuthorities.add(role.name()));
        user.effectivePermissions().forEach(permission -> currentAuthorities.add(permission.name()));
        if (!currentAuthorities.equals(authorities)) return false;
        RefreshTokenSession session = sessions.findActiveFamily(userId, familyId, Instant.now()).orElse(null);
        if (session == null) return false;
        boolean enrolled = credentials.existsByUserIdAndEnrolledAtIsNotNull(userId);
        return !policy.required(user, enrolled) || (enrolled && effectiveProof(session, tokenVerifiedAt) != null);
    }

    public static Instant effectiveProof(RefreshTokenSession session, Instant tokenVerifiedAt) {
        Instant stored = session.getMfaVerifiedAt();
        if (stored == null || tokenVerifiedAt == null || tokenVerifiedAt.isAfter(stored) || tokenVerifiedAt.isAfter(Instant.now())) return null;
        return tokenVerifiedAt;
    }

    private UserAccount requireUserForUpdate(UUID userId) {
        UserAccount user = users.findByIdForUpdate(userId).orElseThrow(this::invalidSession);
        requireActive(user);
        return user;
    }
    private UserAccount requireUser(UUID userId) {
        UserAccount user = users.findById(userId).orElseThrow(this::invalidSession);
        requireActive(user);
        return user;
    }
    private void requireActive(UserAccount user) {
        if (!user.isEnabled() || user.isArchived() || user.isLocked()) throw invalidSession();
        if (user.isForcePasswordChange()) throw problem("PASSWORD_CHANGE_REQUIRED", "Change your temporary password first", HttpStatus.FORBIDDEN);
    }
    private RefreshTokenSession requireSession(UUID userId, UUID familyId) {
        if (familyId == null) throw invalidSession();
        return sessions.findActiveFamily(userId, familyId, Instant.now()).orElseThrow(this::invalidSession);
    }
    private void requireFreshProof(RefreshTokenSession session, Instant tokenVerifiedAt) {
        Instant proof = effectiveProof(session, tokenVerifiedAt);
        if (proof == null || !proof.plus(policy.stepUpAge()).isAfter(Instant.now())) {
            throw problem("MFA_STEP_UP_REQUIRED", "Verify an authenticator or recovery code before this action", HttpStatus.FORBIDDEN);
        }
    }
    private void requireAttemptsAvailable(MfaCredential credential, Instant now) {
        if (credential.attemptsBlocked(now)) throw new SecurityCodeRejected("MFA_RATE_LIMITED",
                "Too many security attempts. Try again after 15 minutes", HttpStatus.TOO_MANY_REQUESTS);
    }
    private SecurityCodeRejected rejected(MfaCredential credential, Instant now) {
        credential.recordFailure(now);
        if (credential.attemptsBlocked(now)) return new SecurityCodeRejected("MFA_RATE_LIMITED",
                "Too many security attempts. Try again after 15 minutes", HttpStatus.TOO_MANY_REQUESTS);
        return new SecurityCodeRejected("INVALID_MFA_CODE", "The security code is invalid, expired or already used", HttpStatus.BAD_REQUEST);
    }
    private List<String> newRecoveryCodes() {
        List<String> result = new ArrayList<>(10);
        for (int index = 0; index < 10; index++) {
            byte[] bytes = new byte[16]; random.nextBytes(bytes);
            String hex = HexFormat.of().withUpperCase().formatHex(bytes);
            result.add(hex.substring(0, 8) + "-" + hex.substring(8, 16) + "-" + hex.substring(16, 24) + "-" + hex.substring(24));
        }
        return List.copyOf(result);
    }
    private boolean isRecoveryCode(String code) {
        return code != null && code.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{8}){3}");
    }
    private String recoveryHash(UUID userId, String value) {
        try {
            byte[] input = (userId + ":" + value.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.US_ASCII);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private BusinessException invalidSession() { return problem("SESSION_REVOKED", "Sign in again to continue", HttpStatus.UNAUTHORIZED); }
    private BusinessException problem(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    public record Enrollment(String secret, String otpauthUri) {}
    public record Verification(AuthenticationService.TokenPair tokens, List<String> recoveryCodes) {}
    public record SecurityState(boolean mfaRequired, boolean mfaEnrolled, boolean mfaVerified, Instant mfaVerifiedAt,
                                boolean stepUpRequired, Instant stepUpExpiresAt, int recoveryCodesRemaining,
                                UUID currentSessionId, Set<String> privilegedRoles) {}
    public record SessionView(UUID familyId, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
                              Instant mfaVerifiedAt, boolean current) {}
    public record SessionList(List<SessionView> sessions, int limit, int page, boolean hasMore) {}
    public static class SecurityCodeRejected extends BusinessException {
        SecurityCodeRejected(String code, String message, HttpStatus status) { super(code, message, status); }
    }
}
