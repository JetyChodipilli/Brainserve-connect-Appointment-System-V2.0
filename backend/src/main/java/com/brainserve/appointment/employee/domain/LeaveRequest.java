package com.brainserve.appointment.employee.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "employee_leave_request")
public class LeaveRequest extends AuditableEntity {
    @Column(name = "employee_id", nullable = false) private UUID employeeId;
    @Column(name = "requester_user_id", nullable = false) private UUID requesterUserId;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "end_date", nullable = false) private LocalDate endDate;
    @Column(nullable = false, length = 1000) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private LeaveRequestStatus status = LeaveRequestStatus.PENDING;
    @Column(name = "decided_by_user_id") private UUID decidedByUserId;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_reason", length = 500) private String decisionReason;

    protected LeaveRequest() {}
    public LeaveRequest(UUID employeeId, UUID requesterUserId, LocalDate startDate, LocalDate endDate, String reason) {
        if (endDate.isBefore(startDate)) throw new BusinessException("INVALID_LEAVE_RANGE",
                "Leave end date cannot be before the start date", HttpStatus.UNPROCESSABLE_ENTITY);
        this.employeeId = employeeId; this.requesterUserId = requesterUserId; this.startDate = startDate;
        this.endDate = endDate; this.reason = reason.trim();
    }
    public void decide(LeaveRequestStatus decision, UUID actor, String remarks) {
        if (status != LeaveRequestStatus.PENDING || (decision != LeaveRequestStatus.APPROVED && decision != LeaveRequestStatus.REJECTED))
            throw new BusinessException("INVALID_LEAVE_TRANSITION", "Only a pending leave request can be approved or rejected", HttpStatus.UNPROCESSABLE_ENTITY);
        status = decision; decidedByUserId = actor; decidedAt = Instant.now();
        decisionReason = remarks == null || remarks.isBlank() ? null : remarks.trim();
    }
    public void cancel(UUID actor) {
        if (status != LeaveRequestStatus.PENDING || !requesterUserId.equals(actor))
            throw new BusinessException("LEAVE_CANNOT_BE_CANCELLED", "Only your pending leave request can be cancelled", HttpStatus.UNPROCESSABLE_ENTITY);
        status = LeaveRequestStatus.CANCELLED;
    }
    public UUID getEmployeeId() { return employeeId; }
    public UUID getRequesterUserId() { return requesterUserId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getReason() { return reason; }
    public LeaveRequestStatus getStatus() { return status; }
    public UUID getDecidedByUserId() { return decidedByUserId; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionReason() { return decisionReason; }
}
