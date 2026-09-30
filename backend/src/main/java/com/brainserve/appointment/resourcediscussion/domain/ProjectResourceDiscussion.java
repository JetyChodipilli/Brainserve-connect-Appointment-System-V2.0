package com.brainserve.appointment.resourcediscussion.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_resource_discussion")
public class ProjectResourceDiscussion extends AuditableEntity {
    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private UUID requestedByUserId;
    @Column(name = "hr_recipient_user_id", nullable = false, updatable = false)
    private UUID hrRecipientUserId;
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;
    @Column(name = "project_name", nullable = false, length = 160)
    private String projectName;
    @Column(name = "required_roles", nullable = false, length = 500)
    private String requiredRoles;
    @Column(name = "requested_headcount", nullable = false)
    private int requestedHeadcount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResourcePriority priority;
    @Column(name = "preferred_at", nullable = false)
    private Instant preferredAt;
    @Column(nullable = false, length = 1000)
    private String justification;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ResourceDiscussionStatus status = ResourceDiscussionStatus.REQUESTED;
    @Column(name = "hr_response", length = 1000)
    private String hrResponse;
    @Column(name = "scheduled_at")
    private Instant scheduledAt;
    @Column(name = "hr_decided_at")
    private Instant hrDecidedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected ProjectResourceDiscussion() {}

    public ProjectResourceDiscussion(UUID requestedByUserId, UUID hrRecipientUserId, UUID departmentId,
                                     String projectName, String requiredRoles, int requestedHeadcount,
                                     ResourcePriority priority, Instant preferredAt, String justification) {
        this.requestedByUserId = requestedByUserId; this.hrRecipientUserId = hrRecipientUserId;
        this.departmentId = departmentId; this.projectName = projectName.trim();
        this.requiredRoles = requiredRoles.trim(); this.requestedHeadcount = requestedHeadcount;
        this.priority = priority; this.preferredAt = preferredAt; this.justification = justification.trim();
    }

    public void schedule(String response, Instant meetingAt) {
        requireHrActionable();
        if (meetingAt == null || !meetingAt.isAfter(Instant.now())) throw new BusinessException(
                "INVALID_RESOURCE_MEETING_TIME", "The scheduled discussion time must be in the future",
                HttpStatus.UNPROCESSABLE_ENTITY);
        status = ResourceDiscussionStatus.SCHEDULED; hrResponse = normalize(response);
        scheduledAt = meetingAt; hrDecidedAt = Instant.now();
    }

    public void requestInformation(String response) {
        requireHrActionable();
        status = ResourceDiscussionStatus.NEEDS_INFORMATION; hrResponse = required(response);
        scheduledAt = null; hrDecidedAt = Instant.now();
    }

    public void decline(String response) {
        requireHrActionable();
        status = ResourceDiscussionStatus.DECLINED; hrResponse = required(response);
        scheduledAt = null; hrDecidedAt = Instant.now();
    }

    public void revise(String roles, int headcount, Instant preferred, String reason) {
        if (status != ResourceDiscussionStatus.NEEDS_INFORMATION) invalid("revise");
        requiredRoles = roles.trim(); requestedHeadcount = headcount; preferredAt = preferred;
        justification = reason.trim(); status = ResourceDiscussionStatus.REQUESTED;
        hrResponse = null; hrDecidedAt = null;
    }

    public void complete() {
        if (status != ResourceDiscussionStatus.SCHEDULED) invalid("complete");
        status = ResourceDiscussionStatus.COMPLETED; completedAt = Instant.now();
    }

    private void requireHrActionable() {
        if (status != ResourceDiscussionStatus.REQUESTED) invalid("review");
    }
    private void invalid(String action) { throw new BusinessException("INVALID_RESOURCE_DISCUSSION_STATUS",
            "This resource discussion cannot be " + action + "d while it is " + status,
            HttpStatus.CONFLICT); }
    private String required(String value) {
        String normalized = normalize(value);
        if (normalized == null) throw new BusinessException("RESOURCE_RESPONSE_REQUIRED",
                "HR response is required", HttpStatus.UNPROCESSABLE_ENTITY);
        return normalized;
    }
    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public UUID getRequestedByUserId() { return requestedByUserId; }
    public UUID getHrRecipientUserId() { return hrRecipientUserId; }
    public UUID getDepartmentId() { return departmentId; }
    public String getProjectName() { return projectName; }
    public String getRequiredRoles() { return requiredRoles; }
    public int getRequestedHeadcount() { return requestedHeadcount; }
    public ResourcePriority getPriority() { return priority; }
    public Instant getPreferredAt() { return preferredAt; }
    public String getJustification() { return justification; }
    public ResourceDiscussionStatus getStatus() { return status; }
    public String getHrResponse() { return hrResponse; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getHrDecidedAt() { return hrDecidedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
