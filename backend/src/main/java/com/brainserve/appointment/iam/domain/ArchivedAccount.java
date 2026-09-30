package com.brainserve.appointment.iam.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "archived_account")
public class ArchivedAccount extends AuditableEntity {
    @Column(name = "original_user_id", nullable = false, updatable = false)
    private UUID originalUserId;
    @Column(name = "full_name_snapshot", nullable = false, updatable = false, length = 170)
    private String fullNameSnapshot;
    @Column(name = "email_snapshot", nullable = false, updatable = false, length = 180)
    private String emailSnapshot;
    @Column(name = "role_snapshot", nullable = false, updatable = false, length = 60)
    private String roleSnapshot;
    @Column(name = "department_id_snapshot", updatable = false)
    private UUID departmentIdSnapshot;
    @Column(name = "department_name_snapshot", updatable = false, length = 120)
    private String departmentNameSnapshot;
    @Column(name = "employee_id_snapshot", updatable = false)
    private UUID employeeIdSnapshot;
    @Column(name = "employee_number_snapshot", updatable = false, length = 30)
    private String employeeNumberSnapshot;
    @Column(name = "previous_account_status", nullable = false, updatable = false, length = 40)
    private String previousAccountStatus;
    @Column(name = "closure_reason", nullable = false, updatable = false, length = 1000)
    private String closureReason;
    @Column(name = "closure_request_id", nullable = false, updatable = false)
    private UUID closureRequestId;
    @Column(name = "archived_by_user_id", nullable = false, updatable = false)
    private UUID archivedByUserId;
    @Column(name = "archived_at", nullable = false, updatable = false)
    private Instant archivedAt;
    @Column(name = "retention_until", nullable = false, updatable = false)
    private LocalDate retentionUntil;
    @Column(name = "recovered_at")
    private Instant recoveredAt;
    @Column(name = "recovered_by_user_id")
    private UUID recoveredByUserId;
    @Column(name = "recovered_role", length = 60)
    private String recoveredRole;
    @Column(name = "recovered_department_id")
    private UUID recoveredDepartmentId;
    @Column(name = "recovery_reason", length = 1000)
    private String recoveryReason;

    protected ArchivedAccount() {}

    public ArchivedAccount(UUID originalUserId, String fullNameSnapshot, String emailSnapshot,
                           String roleSnapshot, UUID departmentIdSnapshot, String departmentNameSnapshot,
                           UUID employeeIdSnapshot, String employeeNumberSnapshot, String previousAccountStatus,
                           String closureReason, UUID closureRequestId, UUID archivedByUserId,
                           Instant archivedAt, LocalDate retentionUntil) {
        this.originalUserId = originalUserId; this.fullNameSnapshot = fullNameSnapshot;
        this.emailSnapshot = emailSnapshot; this.roleSnapshot = roleSnapshot;
        this.departmentIdSnapshot = departmentIdSnapshot; this.departmentNameSnapshot = departmentNameSnapshot;
        this.employeeIdSnapshot = employeeIdSnapshot; this.employeeNumberSnapshot = employeeNumberSnapshot;
        this.previousAccountStatus = previousAccountStatus; this.closureReason = closureReason;
        this.closureRequestId = closureRequestId; this.archivedByUserId = archivedByUserId;
        this.archivedAt = archivedAt; this.retentionUntil = retentionUntil;
    }

    public UUID getOriginalUserId() { return originalUserId; }
    public String getFullNameSnapshot() { return fullNameSnapshot; }
    public String getEmailSnapshot() { return emailSnapshot; }
    public String getRoleSnapshot() { return roleSnapshot; }
    public UUID getDepartmentIdSnapshot() { return departmentIdSnapshot; }
    public String getDepartmentNameSnapshot() { return departmentNameSnapshot; }
    public UUID getEmployeeIdSnapshot() { return employeeIdSnapshot; }
    public String getEmployeeNumberSnapshot() { return employeeNumberSnapshot; }
    public String getPreviousAccountStatus() { return previousAccountStatus; }
    public String getClosureReason() { return closureReason; }
    public UUID getClosureRequestId() { return closureRequestId; }
    public UUID getArchivedByUserId() { return archivedByUserId; }
    public Instant getArchivedAt() { return archivedAt; }
    public LocalDate getRetentionUntil() { return retentionUntil; }
    public Instant getRecoveredAt() { return recoveredAt; }
    public UUID getRecoveredByUserId() { return recoveredByUserId; }
    public String getRecoveredRole() { return recoveredRole; }
    public UUID getRecoveredDepartmentId() { return recoveredDepartmentId; }
    public String getRecoveryReason() { return recoveryReason; }
    public boolean isRecovered() { return recoveredAt != null; }
    public void markRecovered(UUID actorUserId, String role, UUID departmentId, String reason, Instant when) {
        if (recoveredAt != null) throw new IllegalStateException("This archived account was already recovered");
        recoveredAt = when;
        recoveredByUserId = actorUserId;
        recoveredRole = role;
        recoveredDepartmentId = departmentId;
        recoveryReason = reason.trim().replaceAll("\\s+", " ");
    }
}
