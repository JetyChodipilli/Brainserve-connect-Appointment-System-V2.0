package com.brainserve.appointment.departmenthr.api;

import com.brainserve.appointment.departmenthr.application.DepartmentHrAssignmentService;
import com.brainserve.appointment.departmenthr.domain.DepartmentHrAssignment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/department-hrs")
public class DepartmentHrController {
    private final DepartmentHrAssignmentService service;
    public DepartmentHrController(DepartmentHrAssignmentService service) { this.service = service; }

    @GetMapping("/assignments")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN','SYSTEM_ADMIN')")
    List<Response> assignments() { return service.history().stream().map(Response::from).toList(); }

    @PostMapping("/assignments")
    @PreAuthorize("hasRole('CEO') and hasAuthority('DEPARTMENT_MANAGE')")
    Response assign(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request request) {
        return Response.from(service.assign(actor(jwt), request.departmentId(), request.hrUserId()));
    }

    @PostMapping("/assignments/{id}/end")
    @PreAuthorize("hasRole('CEO') and hasAuthority('DEPARTMENT_MANAGE')")
    Response end(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return Response.from(service.end(actor(jwt), id));
    }

    @GetMapping("/me/assignment")
    @PreAuthorize("hasRole('HR_ADMIN')")
    DepartmentHrDirectory.Assignment mine(@AuthenticationPrincipal Jwt jwt) {
        return service.requireForUser(actor(jwt));
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasRole('CEO') and hasAuthority('DEPARTMENT_MANAGE')")
    List<DepartmentHrAssignmentService.Candidate> candidates() { return service.candidates(); }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record Request(@NotNull UUID departmentId, @NotNull UUID hrUserId) {}
    public record Response(UUID id, UUID departmentId, UUID hrUserId, UUID hrEmployeeId, boolean active,
                           UUID assignedByUserId, Instant assignedAt, UUID endedByUserId, Instant endedAt) {
        static Response from(DepartmentHrAssignment value) { return new Response(value.getId(), value.getDepartmentId(),
                value.getHrUserId(), value.getHrEmployeeId(), value.isActive(), value.getAssignedByUserId(),
                value.getAssignedAt(), value.getEndedByUserId(), value.getEndedAt()); }
    }
}
