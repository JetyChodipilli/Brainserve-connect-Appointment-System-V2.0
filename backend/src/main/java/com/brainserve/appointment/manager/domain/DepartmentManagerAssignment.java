package com.brainserve.appointment.manager.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "department_manager_assignment")
public class DepartmentManagerAssignment extends AuditableEntity {
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;
    @Column(name = "manager_user_id", nullable = false, updatable = false)
    private UUID managerUserId;
    @Column(name = "manager_employee_id", nullable = false, updatable = false)
    private UUID managerEmployeeId;
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

    protected DepartmentManagerAssignment() {}

    public DepartmentManagerAssignment(UUID departmentId, UUID managerUserId, UUID managerEmployeeId,
                                       UUID assignedByUserId) {
        this.departmentId = departmentId;
        this.managerUserId = managerUserId;
        this.managerEmployeeId = managerEmployeeId;
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
    public UUID getManagerUserId() { return managerUserId; }
    public UUID getManagerEmployeeId() { return managerEmployeeId; }
    public boolean isActive() { return active; }
    public UUID getAssignedByUserId() { return assignedByUserId; }
    public Instant getAssignedAt() { return assignedAt; }
    public UUID getEndedByUserId() { return endedByUserId; }
    public Instant getEndedAt() { return endedAt; }
}
