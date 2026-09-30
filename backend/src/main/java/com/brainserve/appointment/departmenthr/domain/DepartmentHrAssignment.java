package com.brainserve.appointment.departmenthr.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "department_hr_assignment")
public class DepartmentHrAssignment extends AuditableEntity {
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;
    @Column(name = "hr_user_id", nullable = false, updatable = false)
    private UUID hrUserId;
    @Column(name = "hr_employee_id", nullable = false, updatable = false)
    private UUID hrEmployeeId;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "assigned_by_user_id", nullable = false, updatable = false)
    private UUID assignedByUserId;
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;
    @Column(name = "ended_by_user_id")
    private UUID endedByUserId;
    @Column(name = "ended_at")
    private Instant endedAt;

    protected DepartmentHrAssignment() {}

    public DepartmentHrAssignment(UUID departmentId, UUID hrUserId, UUID hrEmployeeId, UUID assignedByUserId) {
        this.departmentId = departmentId;
        this.hrUserId = hrUserId;
        this.hrEmployeeId = hrEmployeeId;
        this.assignedByUserId = assignedByUserId;
        this.assignedAt = Instant.now();
    }

    public void end(UUID actorUserId) {
        if (!active) return;
        active = false;
        endedByUserId = actorUserId;
        endedAt = Instant.now();
    }

    public UUID getDepartmentId() { return departmentId; }
    public UUID getHrUserId() { return hrUserId; }
    public UUID getHrEmployeeId() { return hrEmployeeId; }
    public boolean isActive() { return active; }
    public UUID getAssignedByUserId() { return assignedByUserId; }
    public Instant getAssignedAt() { return assignedAt; }
    public UUID getEndedByUserId() { return endedByUserId; }
    public Instant getEndedAt() { return endedAt; }
}
