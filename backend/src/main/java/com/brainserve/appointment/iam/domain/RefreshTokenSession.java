package com.brainserve.appointment.iam.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "iam_refresh_token_session")
public class RefreshTokenSession extends AuditableEntity {
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "family_id", nullable = false)
    private UUID familyId;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "replaced_by_hash", length = 64)
    private String replacedByHash;

    @Column(name = "mfa_verified_at")
    private Instant mfaVerifiedAt;
    @Column(name = "session_started_at", nullable = false)
    private Instant sessionStartedAt;

    protected RefreshTokenSession() {}
    public RefreshTokenSession(UUID userId, String tokenHash, UUID familyId, Instant expiresAt) {
        this(userId, tokenHash, familyId, expiresAt, Instant.now(), null);
    }
    public RefreshTokenSession(UUID userId, String tokenHash, UUID familyId, Instant expiresAt,
                               Instant sessionStartedAt, Instant mfaVerifiedAt) {
        this.userId = userId; this.tokenHash = tokenHash; this.familyId = familyId; this.expiresAt = expiresAt;
        this.sessionStartedAt = sessionStartedAt; this.mfaVerifiedAt = mfaVerifiedAt;
    }
    public UUID getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getMfaVerifiedAt() { return mfaVerifiedAt; }
    public Instant getSessionStartedAt() { return sessionStartedAt; }
    public UUID getFamilyId() { return familyId; }
    public boolean isUsable() { return revokedAt == null && expiresAt.isAfter(Instant.now()); }
    public boolean isRevoked() { return revokedAt != null; }
    public void rotateTo(String nextHash) { revokedAt = Instant.now(); replacedByHash = nextHash; }
    public void revoke() { revokedAt = Instant.now(); }
}
