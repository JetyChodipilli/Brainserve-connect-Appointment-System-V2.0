package com.brainserve.appointment.iam.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "iam_account_recovery_request")
public class AccountRecoveryRequest extends AuditableEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_type", nullable = false, length = 20)
    private AccountRecoveryType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountRecoveryStatus status = AccountRecoveryStatus.PENDING;

    @Column(name = "code_hash", unique = true, length = 64)
    private String codeHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_user_id")
    private UserAccount approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by_user_id")
    private UserAccount rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "used_at")
    private Instant usedAt;

    protected AccountRecoveryRequest() {}

    public AccountRecoveryRequest(UserAccount user, AccountRecoveryType type) {
        this.user = user;
        this.type = type;
    }

    public UserAccount getUser() { return user; }
    public AccountRecoveryType getType() { return type; }
    public AccountRecoveryStatus getStatus() { return status; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRejectedAt() { return rejectedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getUsedAt() { return usedAt; }
    public UserAccount getApprovedBy() { return approvedBy; }
    public UserAccount getRejectedBy() { return rejectedBy; }

    public void approve(UserAccount approver, String nextCodeHash, Instant nextExpiresAt) {
        requirePending();
        if (nextCodeHash == null || nextCodeHash.length() != 64 || !nextExpiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("A valid recovery code hash and future expiry are required");
        }
        status = AccountRecoveryStatus.APPROVED;
        approvedBy = approver;
        approvedAt = Instant.now();
        expiresAt = nextExpiresAt;
        codeHash = nextCodeHash;
    }

    public void reject(UserAccount rejector, String reason) {
        requirePending();
        status = AccountRecoveryStatus.REJECTED;
        rejectedBy = rejector;
        rejectedAt = Instant.now();
        rejectionReason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public void use() {
        if (!isUsableAt(Instant.now())) throw new IllegalStateException("Recovery request is not usable");
        status = AccountRecoveryStatus.USED;
        usedAt = Instant.now();
        codeHash = null;
    }

    public boolean isUsableAt(Instant now) {
        return status == AccountRecoveryStatus.APPROVED && expiresAt != null && expiresAt.isAfter(now);
    }

    private void requirePending() {
        if (status != AccountRecoveryStatus.PENDING) {
            throw new IllegalStateException("Only pending recovery requests can be decided");
        }
    }
}
