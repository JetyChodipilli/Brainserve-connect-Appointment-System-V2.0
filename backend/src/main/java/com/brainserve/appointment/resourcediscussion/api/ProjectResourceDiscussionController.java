package com.brainserve.appointment.resourcediscussion.api;

import com.brainserve.appointment.resourcediscussion.application.ProjectResourceDiscussionService;
import com.brainserve.appointment.resourcediscussion.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resource-discussions")
public class ProjectResourceDiscussionController {
    private final ProjectResourceDiscussionService service;
    public ProjectResourceDiscussionController(ProjectResourceDiscussionService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEAM_LEAD') and hasAuthority('INTERNAL_NOTIFICATION_SEND')")
    DiscussionResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
        return DiscussionResponse.from(service.create(userId(jwt), request.hrRecipientUserId(),
                new ProjectResourceDiscussionService.CreateCommand(request.projectName(), request.requiredRoles(),
                        request.requestedHeadcount(), request.priority(), request.preferredAt(),
                        request.justification())));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEAM_LEAD','HR_ADMIN','CEO')")
    List<DiscussionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt)).stream().map(DiscussionResponse::from).toList();
    }

    @PostMapping("/{id}/hr-action")
    @PreAuthorize("hasRole('HR_ADMIN')")
    DiscussionResponse hrAction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody HrActionRequest request) {
        return DiscussionResponse.from(service.hrAction(userId(jwt), id, request.action(),
                request.response(), request.scheduledAt()));
    }

    @PostMapping("/{id}/revise")
    @PreAuthorize("hasRole('TEAM_LEAD')")
    DiscussionResponse revise(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                              @Valid @RequestBody ReviseRequest request) {
        return DiscussionResponse.from(service.revise(userId(jwt), id,
                new ProjectResourceDiscussionService.ReviseCommand(request.requiredRoles(),
                        request.requestedHeadcount(), request.preferredAt(), request.justification())));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','HR_ADMIN')")
    DiscussionResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return DiscussionResponse.from(service.complete(userId(jwt), id));
    }

    private UUID userId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record CreateRequest(@NotNull UUID hrRecipientUserId,
                                @NotBlank @Size(max = 160) String projectName,
                                @NotBlank @Size(max = 500) String requiredRoles,
                                @Min(1) @Max(100) int requestedHeadcount,
                                @NotNull ResourcePriority priority,
                                @NotNull @Future Instant preferredAt,
                                @NotBlank @Size(min = 5, max = 1000) String justification) {}
    public record HrActionRequest(@NotNull ProjectResourceDiscussionService.HrAction action,
                                  @Size(max = 1000) String response,
                                  Instant scheduledAt) {}
    public record ReviseRequest(@NotBlank @Size(max = 500) String requiredRoles,
                                @Min(1) @Max(100) int requestedHeadcount,
                                @NotNull @Future Instant preferredAt,
                                @NotBlank @Size(min = 5, max = 1000) String justification) {}
    public record DiscussionResponse(UUID id, UUID requestedByUserId, UUID hrRecipientUserId,
                                     UUID departmentId, String projectName, String requiredRoles,
                                     int requestedHeadcount, ResourcePriority priority, Instant preferredAt,
                                     String justification, ResourceDiscussionStatus status, String hrResponse,
                                     Instant scheduledAt, Instant hrDecidedAt, Instant completedAt,
                                     Instant createdAt, long version) {
        static DiscussionResponse from(ProjectResourceDiscussion value) { return new DiscussionResponse(
                value.getId(), value.getRequestedByUserId(), value.getHrRecipientUserId(), value.getDepartmentId(),
                value.getProjectName(), value.getRequiredRoles(), value.getRequestedHeadcount(), value.getPriority(),
                value.getPreferredAt(), value.getJustification(), value.getStatus(), value.getHrResponse(),
                value.getScheduledAt(), value.getHrDecidedAt(), value.getCompletedAt(), value.getCreatedAt(),
                value.getVersion()); }
    }
}
