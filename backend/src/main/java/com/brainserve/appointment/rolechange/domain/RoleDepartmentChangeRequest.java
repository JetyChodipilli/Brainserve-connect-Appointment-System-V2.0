package com.brainserve.appointment.rolechange.domain;

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
@Table(name = "role_department_change_request")
public class RoleDepartmentChangeRequest extends AuditableEntity {
    public enum RoleType { HR_ADMIN, TEAM_LEAD }
    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }
    public enum Resolution { MOVE, REPLACE, SWAP }

    @Column(name = "requester_user_id", nullable = false, updatable = false)
    private UUID requesterUserId;
    @Column(name = "requester_employee_id", updatable = false)
    private UUID requesterEmployeeId;
    @Enumerated(EnumType.STRING)
    @Column(name = "requester_role", nullable = false, updatable = false, length = 30)
    private RoleType requesterRole;
    @Column(name = "from_department_id", updatable = false)
    private UUID fromDepartmentId;
    @Column(name = "target_department_id", nullable = false, updatable = false)
    private UUID targetDepartmentId;
    @Column(name = "target_occupant_user_id", updatable = false)
    private UUID targetOccupantUserId;
    @Column(name = "target_occupant_employee_id", updatable = false)
    private UUID targetOccupantEmployeeId;
    @Column(nullable = false, length = 500)
    private String reason;
    @Column(name = "profile_phone_number", length = 30)
    private String profilePhoneNumber;
    @Column(name = "profile_designation", length = 120)
    private String profileDesignation;
    @Column(name = "profile_joining_date")
    private LocalDate profileJoiningDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Resolution resolution;
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    protected RoleDepartmentChangeRequest() {}

    public RoleDepartmentChangeRequest(UUID requesterUserId, UUID requesterEmployeeId, RoleType requesterRole,
                                       UUID fromDepartmentId, UUID targetDepartmentId,
                                       UUID targetOccupantUserId, UUID targetOccupantEmployeeId,
                                       String reason, String profilePhoneNumber,
                                       String profileDesignation, LocalDate profileJoiningDate) {
        this.requesterUserId = requesterUserId;
        this.requesterEmployeeId = requesterEmployeeId;
        this.requesterRole = requesterRole;
        this.fromDepartmentId = fromDepartmentId;
        this.targetDepartmentId = targetDepartmentId;
        this.targetOccupantUserId = targetOccupantUserId;
        this.targetOccupantEmployeeId = targetOccupantEmployeeId;
        this.reason = reason.trim().replaceAll("\\s+", " ");
        this.profilePhoneNumber = normalize(profilePhoneNumber);
        this.profileDesignation = normalize(profileDesignation);
        this.profileJoiningDate = profileJoiningDate;
        this.requestedAt = Instant.now();
    }

    public void approve(UUID actorUserId, Resolution approvedResolution, String note) {
        requirePending();
        status = Status.APPROVED;
        resolution = approvedResolution;
        decisionNote = normalize(note);
        decidedByUserId = actorUserId;
        decidedAt = Instant.now();
    }

    public void reject(UUID actorUserId, String note) {
        requirePending();
        status = Status.REJECTED;
        decisionNote = normalize(note);
        decidedByUserId = actorUserId;
        decidedAt = Instant.now();
    }

    public void cancel(UUID actorUserId) {
        requirePending();
        if (!requesterUserId.equals(actorUserId)) throw new IllegalArgumentException("Only the requester can cancel");
        status = Status.CANCELLED;
        decidedByUserId = actorUserId;
        decidedAt = Instant.now();
    }

    private void requirePending() {
        if (status != Status.PENDING) throw new IllegalStateException("Department change request is not pending");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().replaceAll("\\s+", " ");
    }

    public UUID getRequesterUserId() { return requesterUserId; }
    public UUID getRequesterEmployeeId() { return requesterEmployeeId; }
    public RoleType getRequesterRole() { return requesterRole; }
    public UUID getFromDepartmentId() { return fromDepartmentId; }
    public UUID getTargetDepartmentId() { return targetDepartmentId; }
    public UUID getTargetOccupantUserId() { return targetOccupantUserId; }
    public UUID getTargetOccupantEmployeeId() { return targetOccupantEmployeeId; }
    public String getReason() { return reason; }
    public String getProfilePhoneNumber() { return profilePhoneNumber; }
    public String getProfileDesignation() { return profileDesignation; }
    public LocalDate getProfileJoiningDate() { return profileJoiningDate; }
    public Status getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getDecidedByUserId() { return decidedByUserId; }
    public Instant getDecidedAt() { return decidedAt; }
    public Resolution getResolution() { return resolution; }
    public String getDecisionNote() { return decisionNote; }
}
