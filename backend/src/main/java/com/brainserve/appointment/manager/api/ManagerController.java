package com.brainserve.appointment.manager.api;

import com.brainserve.appointment.manager.application.ManagerAssignmentService;
import com.brainserve.appointment.manager.domain.DepartmentManagerAssignment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/managers")
public class ManagerController {
    private final ManagerAssignmentService service;

    public ManagerController(ManagerAssignmentService service) { this.service = service; }

    @GetMapping("/assignments")
    @PreAuthorize("hasAnyRole('CEO','SYSTEM_ADMIN')")
    List<Response> assignments() { return service.history().stream().map(Response::from).toList(); }

    @PostMapping("/assignments")
    @PreAuthorize("hasAuthority('MANAGER_ASSIGNMENT_MANAGE')")
    Response assign(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request request) {
        return Response.from(service.assign(actor(jwt), request.departmentId(), request.managerUserId()));
    }

    @PostMapping("/assignments/{id}/end")
    @PreAuthorize("hasAuthority('MANAGER_ASSIGNMENT_MANAGE')")
    Response end(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return Response.from(service.end(actor(jwt), id));
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAuthority('MANAGER_ASSIGNMENT_MANAGE')")
    List<ManagerAssignmentService.Candidate> candidates() { return service.candidates(); }

    @GetMapping("/me/assignment")
    @PreAuthorize("hasRole('MANAGER')")
    ManagerDirectory.Assignment mine(@AuthenticationPrincipal Jwt jwt) {
        return service.requireForUser(actor(jwt));
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record Request(@NotNull UUID departmentId, @NotNull UUID managerUserId) {}

    public record Response(UUID id, UUID departmentId, UUID managerUserId,
                           UUID managerEmployeeId, boolean active, UUID assignedByUserId,
                           Instant assignedAt, UUID endedByUserId, Instant endedAt) {
        static Response from(DepartmentManagerAssignment value) {
            return new Response(value.getId(), value.getDepartmentId(), value.getManagerUserId(),
                    value.getManagerEmployeeId(), value.isActive(), value.getAssignedByUserId(),
                    value.getAssignedAt(), value.getEndedByUserId(), value.getEndedAt());
        }
    }
}
