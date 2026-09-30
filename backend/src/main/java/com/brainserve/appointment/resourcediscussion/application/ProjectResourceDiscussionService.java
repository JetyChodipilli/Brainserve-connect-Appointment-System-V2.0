package com.brainserve.appointment.resourcediscussion.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.resourcediscussion.api.ResourceDiscussionEvents;
import com.brainserve.appointment.resourcediscussion.domain.*;
import com.brainserve.appointment.resourcediscussion.infrastructure.ProjectResourceDiscussionRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectResourceDiscussionService {
    private static final String HR = "ROLE_HR_ADMIN";
    private static final String TEAM_LEAD = "ROLE_TEAM_LEAD";
    private static final String CEO = "ROLE_CEO";
    private final ProjectResourceDiscussionRepository discussions;
    private final TeamLeadDirectory teamLeads;
    private final StaffCommunicationDirectory staff;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    public ProjectResourceDiscussionService(ProjectResourceDiscussionRepository discussions,
                                            TeamLeadDirectory teamLeads,
                                            StaffCommunicationDirectory staff,
                                            ApplicationEventPublisher events,
                                            AuditService audit) {
        this.discussions = discussions; this.teamLeads = teamLeads; this.staff = staff;
        this.events = events; this.audit = audit;
    }

    @Transactional
    public ProjectResourceDiscussion create(UUID teamLeadUserId, UUID hrRecipientUserId, CreateCommand command) {
        var lead = teamLeads.requireForUser(teamLeadUserId);
        var hr = staff.requireActive(hrRecipientUserId);
        if (!hr.roles().contains(HR)) throw new BusinessException("HR_RECIPIENT_REQUIRED",
                "A project resource discussion must be sent to an active HR Admin", HttpStatus.UNPROCESSABLE_ENTITY);
        ProjectResourceDiscussion created = discussions.saveAndFlush(new ProjectResourceDiscussion(teamLeadUserId,
                hrRecipientUserId, lead.departmentId(), command.projectName(), command.requiredRoles(),
                command.requestedHeadcount(), command.priority(), command.preferredAt(), command.justification()));
        events.publishEvent(new ResourceDiscussionEvents.NotificationRequested(teamLeadUserId, hrRecipientUserId,
                "Resource discussion " + shortId(created.getId()) + " requested for " + command.projectName()
                        + ": " + command.requestedHeadcount() + " resource(s), " + command.requiredRoles()
                        + ". Preferred meeting: " + command.preferredAt() + "."));
        audit.record("PROJECT_RESOURCE_DISCUSSION_REQUESTED", "RESOURCE_DISCUSSION", created.getId().toString(),
                "{\"departmentId\":\"" + lead.departmentId() + "\"}");
        return created;
    }

    @Transactional(readOnly = true)
    public List<ProjectResourceDiscussion> list(UUID userId) {
        var member = staff.requireActive(userId);
        if (member.roles().contains(CEO)) return discussions.findTop100ByOrderByCreatedAtDesc();
        if (member.roles().contains(HR)) return discussions.findTop100ByHrRecipientUserIdOrderByCreatedAtDesc(userId);
        if (member.roles().contains(TEAM_LEAD)) return discussions.findTop100ByRequestedByUserIdOrderByCreatedAtDesc(userId);
        throw new BusinessException("RESOURCE_DISCUSSION_ROLE_REQUIRED",
                "Only Team Lead, HR Admin or CEO can view resource discussions", HttpStatus.FORBIDDEN);
    }

    @Transactional
    public ProjectResourceDiscussion hrAction(UUID hrUserId, UUID id, HrAction action,
                                              String response, Instant scheduledAt) {
        ProjectResourceDiscussion item = require(id);
        if (!item.getHrRecipientUserId().equals(hrUserId)) throw new BusinessException(
                "RESOURCE_DISCUSSION_ASSIGNED_TO_ANOTHER_HR", "This discussion is assigned to another HR Admin",
                HttpStatus.FORBIDDEN);
        switch (action) {
            case SCHEDULE -> item.schedule(response, scheduledAt);
            case REQUEST_INFORMATION -> item.requestInformation(response);
            case DECLINE -> item.decline(response);
        }
        events.publishEvent(new ResourceDiscussionEvents.NotificationRequested(hrUserId, item.getRequestedByUserId(),
                "HR updated resource discussion " + shortId(id) + " to " + item.getStatus()
                        + (item.getScheduledAt() == null ? "." : " for " + item.getScheduledAt() + ".")
                        + (item.getHrResponse() == null ? "" : " " + item.getHrResponse())));
        audit.record("PROJECT_RESOURCE_DISCUSSION_" + item.getStatus(), "RESOURCE_DISCUSSION", id.toString(), "{}");
        return item;
    }

    @Transactional
    public ProjectResourceDiscussion revise(UUID teamLeadUserId, UUID id, ReviseCommand command) {
        ProjectResourceDiscussion item = requireOwned(teamLeadUserId, id);
        item.revise(command.requiredRoles(), command.requestedHeadcount(), command.preferredAt(),
                command.justification());
        events.publishEvent(new ResourceDiscussionEvents.NotificationRequested(teamLeadUserId,
                item.getHrRecipientUserId(), "Team Lead supplied updated information for resource discussion "
                + shortId(id) + "."));
        audit.record("PROJECT_RESOURCE_DISCUSSION_REVISED", "RESOURCE_DISCUSSION", id.toString(), "{}");
        return item;
    }

    @Transactional
    public ProjectResourceDiscussion complete(UUID userId, UUID id) {
        ProjectResourceDiscussion item = require(id);
        if (!item.getRequestedByUserId().equals(userId) && !item.getHrRecipientUserId().equals(userId)) {
            throw new BusinessException("RESOURCE_DISCUSSION_SCOPE_DENIED",
                    "Only the requesting Team Lead or assigned HR Admin can complete this discussion",
                    HttpStatus.FORBIDDEN);
        }
        item.complete();
        UUID recipient = item.getRequestedByUserId().equals(userId)
                ? item.getHrRecipientUserId() : item.getRequestedByUserId();
        events.publishEvent(new ResourceDiscussionEvents.NotificationRequested(userId, recipient,
                "Resource discussion " + shortId(id) + " was marked completed."));
        audit.record("PROJECT_RESOURCE_DISCUSSION_COMPLETED", "RESOURCE_DISCUSSION", id.toString(), "{}");
        return item;
    }

    private ProjectResourceDiscussion requireOwned(UUID userId, UUID id) {
        ProjectResourceDiscussion item = require(id);
        if (!item.getRequestedByUserId().equals(userId)) throw new BusinessException(
                "RESOURCE_DISCUSSION_SCOPE_DENIED", "This resource discussion belongs to another Team Lead",
                HttpStatus.FORBIDDEN);
        return item;
    }
    private ProjectResourceDiscussion require(UUID id) { return discussions.findById(id).orElseThrow(() ->
            new BusinessException("RESOURCE_DISCUSSION_NOT_FOUND", "Resource discussion was not found",
                    HttpStatus.NOT_FOUND)); }
    private String shortId(UUID id) { return id.toString().substring(0, 8).toUpperCase(); }

    public enum HrAction { SCHEDULE, REQUEST_INFORMATION, DECLINE }
    public record CreateCommand(String projectName, String requiredRoles, int requestedHeadcount,
                                ResourcePriority priority, Instant preferredAt, String justification) {}
    public record ReviseCommand(String requiredRoles, int requestedHeadcount, Instant preferredAt,
                                String justification) {}
}
