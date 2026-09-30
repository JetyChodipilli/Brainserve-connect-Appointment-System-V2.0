package com.brainserve.appointment.workinsight.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "work_task_audit_record")
public class WorkTaskAuditRecord extends AuditableEntity {
    @Column(name = "work_task_id", nullable = false, unique = true, updatable = false)
    private UUID workTaskId;
    @Column(name = "week_start", nullable = false, updatable = false)
    private LocalDate weekStart;
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;
    @Column(name = "department_name", nullable = false, length = 170, updatable = false)
    private String departmentName;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;
    @Column(name = "employee_number", nullable = false, length = 40, updatable = false)
    private String employeeNumber;
    @Column(name = "employee_name", nullable = false, length = 170, updatable = false)
    private String employeeName;
    @Column(name = "team_lead_user_id", nullable = false, updatable = false)
    private UUID teamLeadUserId;
    @Column(name = "team_lead_name", nullable = false, length = 170, updatable = false)
    private String teamLeadName;
    @Column(name = "assigned_by_role", nullable = false, length = 30, updatable = false)
    private String assignedByRole;
    @Column(name = "assignee_role", nullable = false, length = 30, updatable = false)
    private String assigneeRole;
    @Column(name = "task_title", nullable = false, length = 160, updatable = false)
    private String taskTitle;
    @Column(name = "task_status", nullable = false, length = 30)
    private String taskStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "audit_status", nullable = false, length = 30)
    private WorkInsightStatus auditStatus = WorkInsightStatus.PENDING_MANAGER_APPROVAL;
    @Column(name = "hr_audited_by_user_id", nullable = false)
    private UUID hrAuditedByUserId;
    @Column(name = "hr_audited_at", nullable = false)
    private Instant hrAuditedAt;
    @Column(name = "manager_decided_by_user_id")
    private UUID managerDecidedByUserId;
    @Column(name = "manager_decided_at")
    private Instant managerDecidedAt;
    @Column(name = "manager_remarks", length = 1000)
    private String managerRemarks;
    @Column(name = "ceo_decided_by_user_id")
    private UUID ceoDecidedByUserId;
    @Column(name = "ceo_decided_at")
    private Instant ceoDecidedAt;
    @Column(name = "ceo_remarks", length = 1000)
    private String ceoRemarks;
    @Column(name = "rework_requested_by_role", length = 30)
    private String reworkRequestedByRole;
    @Column(name = "rework_reason", length = 1000)
    private String reworkReason;
    @Column(name = "rework_requested_by_user_id")
    private UUID reworkRequestedByUserId;
    @Column(name = "rework_requested_at")
    private Instant reworkRequestedAt;
    @Column(name = "team_lead_rework_guidance", length = 1000)
    private String teamLeadReworkGuidance;
    @Column(name = "team_lead_responded_at")
    private Instant teamLeadRespondedAt;
    @Column(name = "rework_cycle", nullable = false)
    private int reworkCycle;

    protected WorkTaskAuditRecord() {}

    public WorkTaskAuditRecord(UUID workTaskId, LocalDate weekStart, UUID departmentId, String departmentName,
                               UUID employeeId, String employeeNumber, String employeeName,
                               UUID teamLeadUserId, String teamLeadName, String assignedByRole,
                               String assigneeRole, String taskTitle, String taskStatus,
                               UUID hrAuditedByUserId) {
        this.workTaskId = workTaskId; this.weekStart = weekStart; this.departmentId = departmentId;
        this.departmentName = departmentName; this.employeeId = employeeId; this.employeeNumber = employeeNumber;
        this.employeeName = employeeName; this.teamLeadUserId = teamLeadUserId; this.teamLeadName = teamLeadName;
        this.assignedByRole = assignedByRole; this.assigneeRole = assigneeRole;
        this.taskTitle = taskTitle; this.taskStatus = taskStatus; this.hrAuditedByUserId = hrAuditedByUserId;
        this.hrAuditedAt = Instant.now();
    }

    public void decideByManager(UUID managerUserId, boolean approved, String remarks) {
        if (auditStatus != WorkInsightStatus.PENDING_MANAGER_APPROVAL) {
            throw new BusinessException("WORK_INSIGHT_MANAGER_DECISION_NOT_PENDING",
                    "This work audit is not waiting for a Manager decision", HttpStatus.CONFLICT);
        }
        String normalizedRemarks = normalize(remarks);
        if (!approved && normalizedRemarks == null) {
            throw new BusinessException("WORK_INSIGHT_REJECTION_REASON_REQUIRED",
                    "A rejection reason is required before returning work for rework",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        auditStatus = approved
                ? WorkInsightStatus.PENDING_CEO_APPROVAL
                : WorkInsightStatus.MANAGER_REWORK_REQUESTED;
        managerDecidedByUserId = managerUserId;
        managerDecidedAt = Instant.now();
        managerRemarks = normalizedRemarks;
        if (!approved) recordRework("MANAGER", managerUserId, normalizedRemarks);
    }

    public void decideByCeo(UUID ceoUserId, boolean approved, String remarks) {
        if (auditStatus != WorkInsightStatus.PENDING_CEO_APPROVAL) {
            throw new BusinessException("WORK_INSIGHT_ALREADY_DECIDED",
                    "This work audit is not waiting for the CEO decision", HttpStatus.CONFLICT);
        }
        String normalizedRemarks = normalize(remarks);
        if (!approved && normalizedRemarks == null) {
            throw new BusinessException("WORK_INSIGHT_REJECTION_REASON_REQUIRED",
                    "A rejection reason is required before returning work for rework", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        auditStatus = approved ? WorkInsightStatus.CEO_APPROVED : WorkInsightStatus.CEO_REWORK_REQUESTED;
        ceoDecidedByUserId = ceoUserId; ceoDecidedAt = Instant.now();
        ceoRemarks = normalizedRemarks;
        if (!approved) recordRework("CEO", ceoUserId, normalizedRemarks);
    }

    public void requestHrRework(UUID hrUserId, String reason) {
        if (auditStatus != WorkInsightStatus.PENDING_MANAGER_APPROVAL
                && auditStatus != WorkInsightStatus.PENDING_CEO_APPROVAL
                && auditStatus != WorkInsightStatus.REWORK_ASSIGNED) {
            throw new BusinessException("WORK_INSIGHT_REWORK_ALREADY_PENDING",
                    "This worksheet already has an active Insights decision", HttpStatus.CONFLICT);
        }
        String normalizedReason = normalize(reason);
        if (normalizedReason == null) throw new BusinessException("WORK_INSIGHT_REJECTION_REASON_REQUIRED",
                "HR must explain the flaws before returning work to the Team Lead", HttpStatus.UNPROCESSABLE_ENTITY);
        auditStatus = WorkInsightStatus.HR_REWORK_REQUESTED;
        recordRework("HR_ADMIN", hrUserId, normalizedReason);
    }

    public void assignRework(String guidance, String latestTaskStatus) {
        if (auditStatus != WorkInsightStatus.HR_REWORK_REQUESTED
                && auditStatus != WorkInsightStatus.MANAGER_REWORK_REQUESTED
                && auditStatus != WorkInsightStatus.CEO_REWORK_REQUESTED) {
            throw new BusinessException("WORK_INSIGHT_REWORK_NOT_REQUESTED",
                    "This worksheet is not waiting for a Team Lead rework plan", HttpStatus.CONFLICT);
        }
        String normalizedGuidance = normalize(guidance);
        if (normalizedGuidance == null) throw new BusinessException("WORK_INSIGHT_GUIDANCE_REQUIRED",
                "Team Lead guidance is required before sending rework to the employee", HttpStatus.UNPROCESSABLE_ENTITY);
        teamLeadReworkGuidance = normalizedGuidance;
        teamLeadRespondedAt = Instant.now();
        taskStatus = latestTaskStatus;
        auditStatus = WorkInsightStatus.REWORK_ASSIGNED;
    }

    public void resubmit(UUID hrUserId, String latestTaskStatus) {
        if (auditStatus != WorkInsightStatus.REWORK_ASSIGNED) {
            throw new BusinessException("WORK_INSIGHT_REWORK_NOT_READY",
                    "The returned work is not ready for another HR audit", HttpStatus.CONFLICT);
        }
        taskStatus = latestTaskStatus;
        hrAuditedByUserId = hrUserId;
        hrAuditedAt = Instant.now();
        managerDecidedByUserId = null;
        managerDecidedAt = null;
        managerRemarks = null;
        ceoDecidedByUserId = null;
        ceoDecidedAt = null;
        ceoRemarks = null;
        auditStatus = WorkInsightStatus.PENDING_MANAGER_APPROVAL;
    }

    public void syncTaskStatus(String latestTaskStatus) {
        taskStatus = latestTaskStatus;
    }

    private void recordRework(String role, UUID userId, String reason) {
        reworkRequestedByRole = role;
        reworkReason = reason;
        reworkRequestedByUserId = userId;
        reworkRequestedAt = Instant.now();
        teamLeadReworkGuidance = null;
        teamLeadRespondedAt = null;
        reworkCycle++;
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public UUID getWorkTaskId() { return workTaskId; }
    public LocalDate getWeekStart() { return weekStart; }
    public UUID getDepartmentId() { return departmentId; }
    public String getDepartmentName() { return departmentName; }
    public UUID getEmployeeId() { return employeeId; }
    public String getEmployeeNumber() { return employeeNumber; }
    public String getEmployeeName() { return employeeName; }
    public UUID getTeamLeadUserId() { return teamLeadUserId; }
    public String getTeamLeadName() { return teamLeadName; }
    public String getAssignedByRole() { return assignedByRole; }
    public String getAssigneeRole() { return assigneeRole; }
    public String getTaskTitle() { return taskTitle; }
    public String getTaskStatus() { return taskStatus; }
    public WorkInsightStatus getAuditStatus() { return auditStatus; }
    public UUID getHrAuditedByUserId() { return hrAuditedByUserId; }
    public Instant getHrAuditedAt() { return hrAuditedAt; }
    public UUID getManagerDecidedByUserId() { return managerDecidedByUserId; }
    public Instant getManagerDecidedAt() { return managerDecidedAt; }
    public String getManagerRemarks() { return managerRemarks; }
    public UUID getCeoDecidedByUserId() { return ceoDecidedByUserId; }
    public Instant getCeoDecidedAt() { return ceoDecidedAt; }
    public String getCeoRemarks() { return ceoRemarks; }
    public String getReworkRequestedByRole() { return reworkRequestedByRole; }
    public String getReworkReason() { return reworkReason; }
    public UUID getReworkRequestedByUserId() { return reworkRequestedByUserId; }
    public Instant getReworkRequestedAt() { return reworkRequestedAt; }
    public String getTeamLeadReworkGuidance() { return teamLeadReworkGuidance; }
    public Instant getTeamLeadRespondedAt() { return teamLeadRespondedAt; }
    public int getReworkCycle() { return reworkCycle; }
}
