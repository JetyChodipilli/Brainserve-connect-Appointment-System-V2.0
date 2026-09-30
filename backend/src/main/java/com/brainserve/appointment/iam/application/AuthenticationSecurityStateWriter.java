package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.domain.UserAccount;
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

    public AuthenticationSecurityStateWriter(UserAccountRepository users,
                                             RefreshTokenSessionRepository sessions) {
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
        var current = sessions.findByTokenHashForUpdate(currentHash).orElse(null);
        if (current == null) return RefreshRotation.INVALID;
        if (current.isRevoked()) {
            sessions.revokeFamily(current.getFamilyId(), Instant.now());
            return RefreshRotation.REUSED;
        }
        if (!current.isUsable() || !current.getUserId().equals(expectedUserId)) {
            return RefreshRotation.INVALID;
        }
        current.rotateTo(nextHash);
        sessions.save(new com.brainserve.appointment.iam.domain.RefreshTokenSession(
                expectedUserId, nextHash, current.getFamilyId(), nextExpiresAt));
        return RefreshRotation.ROTATED;
    }

    /**
     * Logout revokes the whole browser-session family and is serialized with
     * rotation, so a refresh racing with logout cannot leave a successor token.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokePresentedRefreshToken(String tokenHash, Instant revokedAt) {
        sessions.findByTokenHashForUpdate(tokenHash)
                .ifPresent(current -> sessions.revokeFamily(current.getFamilyId(), revokedAt));
    }

    public enum RefreshRotation { ROTATED, REUSED, INVALID }
}
