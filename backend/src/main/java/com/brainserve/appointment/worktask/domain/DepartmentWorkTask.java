package com.brainserve.appointment.worktask.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "department_work_task")
public class DepartmentWorkTask extends AuditableEntity {
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;
    @Column(name = "original_employee_id", nullable = false, updatable = false)
    private UUID originalEmployeeId;
    @Column(name = "assignment_revision", nullable = false)
    private long assignmentRevision;
    @Column(name = "team_lead_user_id", nullable = false)
    private UUID teamLeadUserId;
    @Column(name = "assigned_by_user_id", nullable = false, updatable = false)
    private UUID assignedByUserId;
    @Column(name = "assigned_by_role", nullable = false, length = 30, updatable = false)
    private String assignedByRole;
    @Column(name = "assignee_role", nullable = false, length = 30, updatable = false)
    private String assigneeRole;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(nullable = false, length = 1000)
    private String description;
    @Column(name = "department_branch", nullable = false, length = 170, updatable = false)
    private String departmentBranch;
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkTaskStatus status = WorkTaskStatus.ASSIGNED;
    @Column(name = "employee_update", length = 1000)
    private String employeeUpdate;
    @Column(name = "team_lead_review", length = 1000)
    private String teamLeadReview;
    @Column(name = "insight_review_source", length = 30)
    private String insightReviewSource;
    @Column(name = "insight_review_reason", length = 1000)
    private String insightReviewReason;
    @Column(name = "insight_review_requested_at")
    private Instant insightReviewRequestedAt;
    @Column(name = "rework_cycle", nullable = false)
    private int reworkCycle;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    // Null means no submission has been tracked since V55; old cycles are not reconstructed.
    @Column(name = "submission_version")
    private Long submissionVersion;

    @Column(nullable = false, length = 10)
    private String priority = "NORMAL";
    @Column(name = "original_due_date")
    private LocalDate originalDueDate;
    @Column(name = "original_due_date_known", nullable = false)
    private boolean originalDueDateKnown;
    @Column(name = "estimate_minutes")
    private Integer estimateMinutes;
    @Column(name = "evidence_required", nullable = false)
    private boolean evidenceRequired;
    @Column(nullable = false)
    private boolean blocked;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "planning_state", nullable = false, columnDefinition = "jsonb")
    private TaskPlanningState planning = new TaskPlanningState();

    protected DepartmentWorkTask() {}

    public DepartmentWorkTask(UUID departmentId, UUID employeeId, UUID teamLeadUserId,
                              UUID assignedByUserId, String assignedByRole, String assigneeRole,
                              String title, String description, String departmentBranch, LocalDate dueDate) {
        this.departmentId = departmentId;
        this.employeeId = employeeId;
        this.originalEmployeeId = employeeId;
        this.teamLeadUserId = teamLeadUserId;
        this.assignedByUserId = assignedByUserId;
        this.assignedByRole = assignedByRole;
        this.assigneeRole = assigneeRole;
        this.title = title.trim();
        this.description = description.trim();
        this.departmentBranch = departmentBranch.trim();
        this.dueDate = dueDate;
        this.originalDueDate = dueDate;
        this.originalDueDateKnown = true;
    }

    public void start(String update) {
        if (status != WorkTaskStatus.ASSIGNED && status != WorkTaskStatus.CHANGES_REQUESTED) invalid("started");
        status = WorkTaskStatus.IN_PROGRESS;
        employeeUpdate = normalize(update);
        startedAt = Instant.now();
        completedAt = null;
        approvedAt = null;
        acknowledgedAt = null;
    }

    public void complete(String update) {
        complete(update,null,null);
    }
    public void complete(String update, UUID authorUserId, String authorName) {
        if (status != WorkTaskStatus.ASSIGNED && status != WorkTaskStatus.IN_PROGRESS
                && status != WorkTaskStatus.CHANGES_REQUESTED) invalid("completed");
        planning.requireSubmission(evidenceRequired);
        status = WorkTaskStatus.COMPLETED;
        employeeUpdate = normalize(update);
        if (startedAt == null) startedAt = Instant.now();
        completedAt = Instant.now();
        submissionVersion = submissionVersion == null ? 1L : submissionVersion + 1;
        planning.capture(submissionVersion, completedAt,authorUserId==null?null:employeeId,authorUserId,authorName,assignmentRevision,employeeUpdate);
        approvedAt = null;
        acknowledgedAt = null;
    }

    public void requestChanges(String review) {
        requireTeamLeadReview("returned for changes");
        if (status != WorkTaskStatus.COMPLETED) invalid("returned for changes");
        status = WorkTaskStatus.CHANGES_REQUESTED;
        teamLeadReview = required(review, "A review note is required when requesting changes");
        completedAt = null;
        approvedAt = null;
        acknowledgedAt = null;
    }

    public void approve(String review) {
        requireTeamLeadReview("approved");
        if (status != WorkTaskStatus.COMPLETED) invalid("approved");
        status = WorkTaskStatus.APPROVED;
        teamLeadReview = normalize(review);
        approvedAt = Instant.now();
        planning.accept(submissionVersion, "TEAM_LEAD");
        acknowledgedAt = null;
    }

    public void acknowledge() {
        requireTeamLeadReview("acknowledged");
        if (status != WorkTaskStatus.APPROVED) invalid("acknowledged");
        status = WorkTaskStatus.ACKNOWLEDGED;
        acknowledgedAt = Instant.now();
    }

    public void requestInsightRework(String source, String reason) {
        if (!isReadyForHrAudit()) {
            invalid("returned by Insights for rework");
        }
        insightReviewSource = required(source, "The Insights reviewer is required");
        insightReviewReason = required(reason, "A rejection reason is required before requesting rework");
        insightReviewRequestedAt = Instant.now();
        reworkCycle++;
        status = WorkTaskStatus.INSIGHT_REWORK_REQUESTED;
        approvedAt = null;
        acknowledgedAt = null;
    }

    public void assignInsightRework(String guidance) {
        if (status != WorkTaskStatus.INSIGHT_REWORK_REQUESTED) invalid("assigned for rework");
        teamLeadReview = required(guidance, "Team Lead rework guidance is required");
        status = WorkTaskStatus.CHANGES_REQUESTED;
        startedAt = null;
        completedAt = null;
        approvedAt = null;
        acknowledgedAt = null;
    }

    public void reviseInsightReworkSubmission(String update) {
        reviseInsightReworkSubmission(update,null,null);
    }
    public void reviseInsightReworkSubmission(String update,UUID authorUserId,String authorName) {
        if (!"TEAM_LEAD".equals(assigneeRole) || status != WorkTaskStatus.COMPLETED
                || reworkCycle < 1 || insightReviewReason == null) {
            invalid("updated after rework submission");
        }
        planning.requireSubmission(evidenceRequired);
        employeeUpdate = required(update, "A revised completion update is required");
        completedAt = Instant.now();
        submissionVersion = submissionVersion == null ? 1L : submissionVersion + 1;
        planning.capture(submissionVersion, completedAt,authorUserId==null?null:employeeId,authorUserId,authorName,assignmentRevision,employeeUpdate);
    }

    public void reviseEmployeeReworkSubmission(String update) {
        reviseEmployeeReworkSubmission(update,null,null);
    }
    public void reviseEmployeeReworkSubmission(String update,UUID authorUserId,String authorName) {
        if (!"EMPLOYEE".equals(assigneeRole)) {
            throw new BusinessException("WORK_TASK_REWORK_UPDATE_DENIED",
                    "Only the assigned Employee can update this rework submission",
                    HttpStatus.FORBIDDEN);
        }
        if (status != WorkTaskStatus.COMPLETED || teamLeadReview == null) {
            throw new BusinessException("WORK_TASK_REWORK_UPDATE_CLOSED",
                    "This rework submission can no longer be updated because the Team Lead has already reviewed it",
                    HttpStatus.CONFLICT);
        }
        planning.requireSubmission(evidenceRequired);
        employeeUpdate = required(update, "A revised completion update is required");
        completedAt = Instant.now();
        submissionVersion = submissionVersion == null ? 1L : submissionVersion + 1;
        planning.capture(submissionVersion, completedAt,authorUserId==null?null:employeeId,authorUserId,authorName,assignmentRevision,employeeUpdate);
    }

    public void finalizeInsightApproval() {
        if (!"TEAM_LEAD".equals(assigneeRole)) return;
        if (status != WorkTaskStatus.COMPLETED) {
            invalid("finally approved by Insights");
        }
        status = WorkTaskStatus.APPROVED;
        approvedAt = Instant.now();
        acknowledgedAt = null;
    }

    public boolean isOverdue(LocalDate today) {
        return dueDate.isBefore(today) && !isReadyForHrAudit();
    }

    public boolean isReadyForHrAudit() {
        if ("TEAM_LEAD".equals(assigneeRole)) return status == WorkTaskStatus.COMPLETED;
        return status == WorkTaskStatus.APPROVED || status == WorkTaskStatus.ACKNOWLEDGED;
    }

    public boolean requiresTeamLeadReview() {
        return "EMPLOYEE".equals(assigneeRole);
    }

    /** A revised delivery assignment retains prior authored evidence and starts a new draft. */
    public void handover(UUID targetEmployeeId,UUID targetUserId,Instant at) {
        if(employeeId.equals(targetEmployeeId)) throw new BusinessException("WORK_TASK_HANDOVER_INVALID","Choose a different assignee",HttpStatus.UNPROCESSABLE_ENTITY);
        planning.retainDraft(employeeId,assignmentRevision,employeeUpdate,at);
        employeeId=targetEmployeeId;
        if("TEAM_LEAD".equals(assigneeRole)) teamLeadUserId=targetUserId;
        assignmentRevision++;
        status=WorkTaskStatus.ASSIGNED;
        employeeUpdate=null;
        teamLeadReview=null;
        insightReviewSource=null;
        insightReviewReason=null;
        insightReviewRequestedAt=null;
        startedAt=null;completedAt=null;approvedAt=null;acknowledgedAt=null;
    }

    public void reassignOpenTask(UUID expectedTeamLeadUserId, UUID replacementTeamLeadUserId) {
        if (!teamLeadUserId.equals(expectedTeamLeadUserId)) {
            throw new IllegalStateException("The task is owned by another Team Lead");
        }
        if (status == WorkTaskStatus.APPROVED || status == WorkTaskStatus.ACKNOWLEDGED) return;
        teamLeadUserId = replacementTeamLeadUserId;
    }

    private void invalid(String action) {
        throw new BusinessException("INVALID_WORK_TASK_STATUS", "This task cannot be " + action
                + " while it is " + status, HttpStatus.CONFLICT);
    }

    private void requireTeamLeadReview(String action) {
        if (!requiresTeamLeadReview()) {
            throw new BusinessException("WORK_TASK_SELF_REVIEW_NOT_ALLOWED",
                    "An HR-assigned Team Lead worksheet cannot be " + action
                            + " through employee review; completed work goes directly to HR audit",
                    HttpStatus.CONFLICT);
        }
    }

    private String required(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) throw new BusinessException("WORK_TASK_REVIEW_REQUIRED", message,
                HttpStatus.UNPROCESSABLE_ENTITY);
        return normalized;
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public void acceptHrEvidence() { if ("TEAM_LEAD".equals(assigneeRole)) planning.accept(submissionVersion, "HR_ADMIN"); }
    public String getPriority() { return priority; }
    public LocalDate getOriginalDueDate() { return originalDueDate; }
    public boolean isOriginalDueDateKnown() { return originalDueDateKnown; }
    public Integer getEstimateMinutes() { return estimateMinutes; }
    public boolean isEvidenceRequired() { return evidenceRequired; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean value) { blocked = value; }
    public TaskPlanningState getPlanning() { return planning; }
    public void updatePlanning(String priority, Integer estimateMinutes, boolean evidenceRequired, LocalDate dueDate) {
        this.priority = priority; this.estimateMinutes = estimateMinutes; this.evidenceRequired = evidenceRequired; this.dueDate = dueDate;
    }

    public Long getSubmissionVersion() { return submissionVersion; }

    public UUID getDepartmentId() { return departmentId; }
    public UUID getEmployeeId() { return employeeId; }
    public UUID getOriginalEmployeeId() { return originalEmployeeId; }
    public long getAssignmentRevision() { return assignmentRevision; }
    public UUID getTeamLeadUserId() { return teamLeadUserId; }
    public UUID getAssignedByUserId() { return assignedByUserId; }
    public String getAssignedByRole() { return assignedByRole; }
    public String getAssigneeRole() { return assigneeRole; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getDepartmentBranch() { return departmentBranch; }
    public LocalDate getDueDate() { return dueDate; }
    public WorkTaskStatus getStatus() { return status; }
    public String getEmployeeUpdate() { return employeeUpdate; }
    public String getTeamLeadReview() { return teamLeadReview; }
    public String getInsightReviewSource() { return insightReviewSource; }
    public String getInsightReviewReason() { return insightReviewReason; }
    public Instant getInsightReviewRequestedAt() { return insightReviewRequestedAt; }
    public int getReworkCycle() { return reworkCycle; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
}
