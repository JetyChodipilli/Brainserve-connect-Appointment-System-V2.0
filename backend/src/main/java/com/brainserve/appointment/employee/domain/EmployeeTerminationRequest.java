package com.brainserve.appointment.employee.domain;

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
@Table(name = "employee_termination_request")
public class EmployeeTerminationRequest extends AuditableEntity {
    public enum Status { PENDING_CEO_APPROVAL, APPROVED, REJECTED }

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;
    @Column(name = "requested_by_hr_user_id", nullable = false, updatable = false)
    private UUID requestedByHrUserId;
    @Column(nullable = false, updatable = false, length = 1000)
    private String reason;
    @Column(name = "effective_date", nullable = false, updatable = false)
    private LocalDate effectiveDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.PENDING_CEO_APPROVAL;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "decided_by_ceo_user_id")
    private UUID decidedByCeoUserId;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    protected EmployeeTerminationRequest() {}

    public EmployeeTerminationRequest(UUID employeeId, UUID departmentId, UUID requestedByHrUserId,
                                      String reason, LocalDate effectiveDate) {
        this.employeeId = employeeId; this.departmentId = departmentId;
        this.requestedByHrUserId = requestedByHrUserId; this.reason = normalize(reason);
        this.effectiveDate = effectiveDate; this.requestedAt = Instant.now();
    }

    public void approve(UUID ceoUserId, String note) {
        requirePending(); status = Status.APPROVED; decidedByCeoUserId = ceoUserId;
        decidedAt = Instant.now(); decisionNote = nullable(note);
    }

    public void reject(UUID ceoUserId, String note) {
        requirePending(); status = Status.REJECTED; decidedByCeoUserId = ceoUserId;
        decidedAt = Instant.now(); decisionNote = normalize(note);
    }

    private void requirePending() {
        if (status != Status.PENDING_CEO_APPROVAL) throw new IllegalStateException("Termination request is not pending");
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : normalize(value); }

    public UUID getEmployeeId() { return employeeId; }
    public UUID getDepartmentId() { return departmentId; }
    public UUID getRequestedByHrUserId() { return requestedByHrUserId; }
    public String getReason() { return reason; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public Status getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getDecidedByCeoUserId() { return decidedByCeoUserId; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
}
