package com.brainserve.appointment.iam.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "account_closure_request")
public class AccountClosureRequest extends AuditableEntity {
    public enum Origin { SELF_SERVICE, SYSTEM_ADMIN_EMERGENCY, EMPLOYEE_TERMINATION }

    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "target_role", nullable = false, updatable = false, length = 60)
    private SystemRole targetRole;
    @Column(name = "department_id", updatable = false)
    private UUID departmentId;
    @Column(name = "requester_user_id", nullable = false, updatable = false)
    private UUID requesterUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "request_origin", nullable = false, updatable = false, length = 40)
    private Origin origin;
    @Column(nullable = false, updatable = false, length = 1000)
    private String reason;
    @Column(name = "requested_effective_date", nullable = false, updatable = false)
    private LocalDate requestedEffectiveDate;
    @Column(name = "replacement_user_id")
    private UUID replacementUserId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AccountClosureStatus status = AccountClosureStatus.REQUESTED;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "business_approver_user_id")
    private UUID businessApproverUserId;
    @Column(name = "business_approved_at")
    private Instant businessApprovedAt;
    @Column(name = "system_admin_approver_user_id")
    private UUID systemAdminApproverUserId;
    @Column(name = "system_admin_approved_at")
    private Instant systemAdminApprovedAt;
    @Column(name = "decision_note", length = 1000)
    private String decisionNote;
    @Column(name = "scheduled_at")
    private Instant scheduledAt;
    @Column(name = "archived_at")
    private Instant archivedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected AccountClosureRequest() {}

    public AccountClosureRequest(UUID targetUserId, SystemRole targetRole, UUID departmentId,
                                 UUID requesterUserId, Origin origin, String reason,
                                 LocalDate requestedEffectiveDate, UUID replacementUserId) {
        this.targetUserId = targetUserId;
        this.targetRole = targetRole;
        this.departmentId = departmentId;
        this.requesterUserId = requesterUserId;
        this.origin = origin;
        this.reason = normalize(reason);
        this.requestedEffectiveDate = requestedEffectiveDate;
        setReplacementUserId(replacementUserId);
        this.requestedAt = Instant.now();
    }

    public void setReplacementUserId(UUID replacementUserId) {
        if (replacementUserId != null && replacementUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("Replacement account must differ from the closing account");
        }
        this.replacementUserId = replacementUserId;
    }

    public void businessApprove(UUID actorUserId, String note) {
        require(AccountClosureStatus.REQUESTED);
        status = AccountClosureStatus.BUSINESS_APPROVED;
        businessApproverUserId = actorUserId;
        businessApprovedAt = Instant.now();
        decisionNote = nullable(note);
    }

    public void forwardToSystemAdmin() {
        require(AccountClosureStatus.BUSINESS_APPROVED);
        status = AccountClosureStatus.PENDING_SYSTEM_ADMIN;
    }

    public void systemAdminApprove(UUID actorUserId, String note) {
        if (status != AccountClosureStatus.REQUESTED && status != AccountClosureStatus.PENDING_SYSTEM_ADMIN) {
            throw new IllegalStateException("Account closure is not ready for System Admin approval");
        }
        systemAdminApproverUserId = actorUserId;
        systemAdminApprovedAt = Instant.now();
        decisionNote = nullable(note);
    }

    public void schedule() {
        if (systemAdminApprovedAt == null) throw new IllegalStateException("System Admin approval is required");
        status = AccountClosureStatus.SCHEDULED;
        scheduledAt = Instant.now();
    }

    public void archive(Instant when) {
        if (origin != Origin.EMPLOYEE_TERMINATION && systemAdminApprovedAt == null) {
            throw new IllegalStateException("System Admin approval is required");
        }
        if (status == AccountClosureStatus.ARCHIVED) throw new IllegalStateException("Account is already archived");
        status = AccountClosureStatus.ARCHIVED;
        archivedAt = when;
    }

    public void rejectByBusiness(UUID actorUserId, String note) {
        require(AccountClosureStatus.REQUESTED);
        status = AccountClosureStatus.REJECTED;
        businessApproverUserId = actorUserId;
        decisionNote = normalize(note);
    }

    public void rejectBySystemAdmin(UUID actorUserId, String note) {
        if (status == AccountClosureStatus.ARCHIVED || status == AccountClosureStatus.CANCELLED
                || status == AccountClosureStatus.REJECTED) {
            throw new IllegalStateException("Account closure has already been decided");
        }
        status = AccountClosureStatus.REJECTED;
        decisionNote = normalize(note);
        systemAdminApproverUserId = actorUserId;
    }

    public void cancel() {
        if (status != AccountClosureStatus.REQUESTED && status != AccountClosureStatus.PENDING_SYSTEM_ADMIN) {
            throw new IllegalStateException("Only a pending account closure can be cancelled");
        }
        status = AccountClosureStatus.CANCELLED;
        cancelledAt = Instant.now();
    }

    private void require(AccountClosureStatus expected) {
        if (status != expected) throw new IllegalStateException("Expected account closure status " + expected);
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : normalize(value); }

    public UUID getTargetUserId() { return targetUserId; }
    public SystemRole getTargetRole() { return targetRole; }
    public UUID getDepartmentId() { return departmentId; }
    public UUID getRequesterUserId() { return requesterUserId; }
    public Origin getOrigin() { return origin; }
    public String getReason() { return reason; }
    public LocalDate getRequestedEffectiveDate() { return requestedEffectiveDate; }
    public UUID getReplacementUserId() { return replacementUserId; }
    public AccountClosureStatus getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getBusinessApproverUserId() { return businessApproverUserId; }
    public Instant getBusinessApprovedAt() { return businessApprovedAt; }
    public UUID getSystemAdminApproverUserId() { return systemAdminApproverUserId; }
    public Instant getSystemAdminApprovedAt() { return systemAdminApprovedAt; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
