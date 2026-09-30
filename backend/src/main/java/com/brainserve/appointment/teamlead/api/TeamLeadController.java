package com.brainserve.appointment.teamlead.api;

import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.teamlead.application.TeamLeadAssignmentService;
import com.brainserve.appointment.teamlead.domain.DepartmentTeamLeadAssignment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/team-leads")
public class TeamLeadController {
    private final TeamLeadAssignmentService service;
    private final EmployeeDirectory employees;
    public TeamLeadController(TeamLeadAssignmentService service, EmployeeDirectory employees) {
        this.service = service; this.employees = employees;
    }

    @GetMapping("/assignments")
    @PreAuthorize("hasAnyRole('HR_ADMIN','CEO')")
    List<AssignmentResponse> assignments() { return service.history().stream().map(AssignmentResponse::from).toList(); }

    @PostMapping("/assignments")
    @PreAuthorize("hasAuthority('TEAM_LEAD_ASSIGNMENT_MANAGE')")
    AssignmentResponse assign(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AssignRequest request) {
        return AssignmentResponse.from(service.assign(actor(jwt), request.departmentId(), request.employeeId()));
    }

    @PostMapping("/assignments/{id}/end")
    @PreAuthorize("hasAuthority('TEAM_LEAD_ASSIGNMENT_MANAGE')")
    AssignmentResponse end(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return AssignmentResponse.from(service.end(actor(jwt), id));
    }

    @GetMapping("/me/assignment")
    @PreAuthorize("hasAuthority('TEAM_LEAD_DIRECTORY_VIEW') and hasRole('TEAM_LEAD')")
    TeamLeadDirectory.Assignment mine(@AuthenticationPrincipal Jwt jwt) { return service.requireForUser(actor(jwt)); }

    @GetMapping("/me/team")
    @PreAuthorize("hasAuthority('TEAM_LEAD_DIRECTORY_VIEW') and hasRole('TEAM_LEAD')")
    Page<EmployeeDirectory.DepartmentMember> myTeam(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        requireBoundedPage(pageable);
        return employees.departmentMembers(service.requireForUser(actor(jwt)).departmentId(), pageable);
    }

    @GetMapping("/me/workspace")
    @PreAuthorize("hasAuthority('TEAM_LEAD_DIRECTORY_VIEW') and hasRole('TEAM_LEAD')")
    TeamLeadAssignmentService.Workspace workspace(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        requireBoundedPage(pageable);
        return service.workspace(actor(jwt), pageable);
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private void requireBoundedPage(Pageable pageable) {
        if (pageable.getPageSize() < 25 || pageable.getPageSize() > 100) {
            throw new com.brainserve.appointment.shared.application.BusinessException(
                    "INVALID_PAGE_SIZE", "Page size must be between 25 and 100",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
    }
    public record AssignRequest(@NotNull UUID departmentId, @NotNull UUID employeeId) {}
    public record AssignmentResponse(UUID id, UUID departmentId, UUID teamLeadUserId, UUID teamLeadEmployeeId,
                                     boolean active, UUID assignedByUserId, Instant assignedAt,
                                     UUID endedByUserId, Instant endedAt) {
        static AssignmentResponse from(DepartmentTeamLeadAssignment value) { return new AssignmentResponse(
                value.getId(), value.getDepartmentId(), value.getTeamLeadUserId(), value.getTeamLeadEmployeeId(),
                value.isActive(), value.getAssignedByUserId(), value.getAssignedAt(),
                value.getEndedByUserId(), value.getEndedAt()); }
    }
}
