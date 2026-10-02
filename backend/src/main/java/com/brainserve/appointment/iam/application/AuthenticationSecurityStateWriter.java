package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists defensive authentication state independently from a login or token
 * refresh transaction that is expected to fail and roll back.
 */
@Service
public class AuthenticationSecurityStateWriter {
    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository sessions;
    private final AuditService audit;

    public AuthenticationSecurityStateWriter(UserAccountRepository users,
                                             RefreshTokenSessionRepository sessions, AuditService audit) {
        this.audit = audit;
        this.users = users;
        this.sessions = sessions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedLogin(UUID userId) {
        users.findByIdForUpdate(userId).ifPresent(UserAccount::recordFailedLogin);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeRefreshTokenFamily(UUID familyId, Instant revokedAt) {
        sessions.revokeFamily(familyId, revokedAt);
    }

    /**
     * Serializes refresh-token rotation. A second presentation of the same
     * token waits for the first rotation, observes the revoked token and then
     * revokes the complete family before returning a rejected outcome.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefreshRotation rotateRefreshToken(String currentHash, String nextHash,
                                              UUID expectedUserId, Instant nextExpiresAt) {
        sessions.lockSessionOwner(expectedUserId);
        var current = sessions.findByTokenHashForUpdate(currentHash).orElse(null);
        if (current == null) return RefreshRotation.INVALID;
        if (current.isRevoked()) {
            sessions.revokeFamily(current.getFamilyId(), Instant.now());
            audit.record("SESSION_TOKEN_REUSE_REVOKED", "USER_ACCOUNT", current.getUserId().toString(),
                    "{\"familyId\":\"" + current.getFamilyId() + "\"}");
            return RefreshRotation.REUSED;
        }
        if (!current.isUsable() || !current.getUserId().equals(expectedUserId)) {
            return RefreshRotation.INVALID;
        }
        current.rotateTo(nextHash);
        sessions.save(new com.brainserve.appointment.iam.domain.RefreshTokenSession(
                expectedUserId, nextHash, current.getFamilyId(),
                current.getMfaVerifiedAt() == null && current.getExpiresAt().isBefore(nextExpiresAt)
                        ? current.getExpiresAt() : nextExpiresAt,
                current.getSessionStartedAt(), current.getMfaVerifiedAt()));
        return RefreshRotation.ROTATED;
    }

    /**
     * Logout revokes the whole browser-session family and is serialized with
     * rotation, so a refresh racing with logout cannot leave a successor token.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokePresentedRefreshToken(String tokenHash, Instant revokedAt) {
        // Owner first, then token row: the same lock order as refresh and MFA completion.
        sessions.findByTokenHash(tokenHash).ifPresent(presented -> {
            sessions.lockSessionOwner(presented.getUserId());
            sessions.findByTokenHashForUpdate(tokenHash).ifPresent(current -> {
                sessions.revokeFamily(current.getFamilyId(), revokedAt);
                audit.record("SESSION_SIGNED_OUT", "USER_ACCOUNT", current.getUserId().toString(),
                        "{\"familyId\":\"" + current.getFamilyId() + "\"}");
            });
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllUserSessions(UUID userId) {
        sessions.revokeAllForUser(userId, Instant.now());
        audit.record("ALL_SESSIONS_SIGNED_OUT", "USER_ACCOUNT", userId.toString(), "{}");
    }

    public enum RefreshRotation { ROTATED, REUSED, INVALID }
}
