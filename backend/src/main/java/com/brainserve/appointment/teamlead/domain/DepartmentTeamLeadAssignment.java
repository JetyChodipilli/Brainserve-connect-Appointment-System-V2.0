package com.brainserve.appointment.teamlead.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "department_team_lead")
public class DepartmentTeamLeadAssignment extends AuditableEntity {
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;
    @Column(name = "team_lead_user_id", nullable = false, updatable = false)
    private UUID teamLeadUserId;
    @Column(name = "team_lead_employee_id", nullable = false, updatable = false)
    private UUID teamLeadEmployeeId;
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

    protected DepartmentTeamLeadAssignment() {}
    public DepartmentTeamLeadAssignment(UUID departmentId, UUID teamLeadUserId, UUID teamLeadEmployeeId,
                                        UUID assignedByUserId) {
        this.departmentId = departmentId; this.teamLeadUserId = teamLeadUserId;
        this.teamLeadEmployeeId = teamLeadEmployeeId; this.assignedByUserId = assignedByUserId;
        this.assignedAt = Instant.now();
    }
    public void end(UUID actorUserId) {
        if (!active) return;
        active = false; endedByUserId = actorUserId; endedAt = Instant.now();
    }
    public UUID getDepartmentId() { return departmentId; }
    public UUID getTeamLeadUserId() { return teamLeadUserId; }
    public UUID getTeamLeadEmployeeId() { return teamLeadEmployeeId; }
    public boolean isActive() { return active; }
    public UUID getAssignedByUserId() { return assignedByUserId; }
    public Instant getAssignedAt() { return assignedAt; }
    public UUID getEndedByUserId() { return endedByUserId; }
    public Instant getEndedAt() { return endedAt; }
}
