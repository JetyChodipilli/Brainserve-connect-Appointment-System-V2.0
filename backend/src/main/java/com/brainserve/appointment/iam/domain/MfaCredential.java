package com.brainserve.appointment.iam.domain;

import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "iam_mfa_credential")
public class MfaCredential {
    @Id @Column(name = "user_id", nullable = false) private UUID userId;
    @Version @Column(nullable = false) private long version;
    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "secret_ciphertext", length = 512) private String secret;
    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "pending_secret_ciphertext", length = 512) private String pendingSecret;
    @Column(name = "pending_family_id") private UUID pendingFamilyId;
    @Column(name = "pending_expires_at") private Instant pendingExpiresAt;
    @Column(name = "enrolled_at") private Instant enrolledAt;
    @Column(name = "last_accepted_step") private Long lastAcceptedStep;
    @Column(name = "failure_window_at") private Instant failureWindowAt;
    @Column(name = "failed_attempts", nullable = false) private int failedAttempts;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "iam_mfa_recovery_code", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "code_hash", length = 64, nullable = false)
    private Set<String> recoveryHashes = new HashSet<>();

    protected MfaCredential() {}
    public MfaCredential(UUID userId) { this.userId = userId; }
    public UUID getUserId() { return userId; }
    public String getSecret() { return secret; }
    public String getPendingSecret() { return pendingSecret; }
    public boolean isEnrolled() { return enrolledAt != null && secret != null; }
    public Long getLastAcceptedStep() { return lastAcceptedStep; }
    public int recoveryCodesRemaining() { return recoveryHashes.size(); }
    public boolean pendingFor(UUID familyId, Instant now) {
        return pendingSecret != null && familyId.equals(pendingFamilyId) && pendingExpiresAt != null
                && pendingExpiresAt.isAfter(now);
    }
    public void beginEnrollment(String nextSecret, UUID familyId, Instant expiresAt) {
        pendingSecret = nextSecret; pendingFamilyId = familyId; pendingExpiresAt = expiresAt;
    }
    public void confirmEnrollment(Instant when, long step, Set<String> hashes) {
        secret = pendingSecret; enrolledAt = when; lastAcceptedStep = step;
        pendingSecret = null; pendingFamilyId = null; pendingExpiresAt = null;
        recoveryHashes.clear(); recoveryHashes.addAll(hashes); clearFailures();
    }
    public void acceptStep(long step) { lastAcceptedStep = step; clearFailures(); }
    public boolean consumeRecoveryHash(String hash) {
        if (!recoveryHashes.remove(hash)) return false;
        clearFailures(); return true;
    }
    public boolean attemptsBlocked(Instant now) {
        return failedAttempts >= 5 && failureWindowAt != null && failureWindowAt.plus(Duration.ofMinutes(15)).isAfter(now);
    }
    public void recordFailure(Instant now) {
        if (failureWindowAt == null || !failureWindowAt.plus(Duration.ofMinutes(15)).isAfter(now)) {
            failureWindowAt = now; failedAttempts = 0;
        }
        failedAttempts++;
    }
    private void clearFailures() { failureWindowAt = null; failedAttempts = 0; }
}
