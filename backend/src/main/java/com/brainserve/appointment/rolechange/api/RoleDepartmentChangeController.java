package com.brainserve.appointment.rolechange.api;

import com.brainserve.appointment.rolechange.application.RoleDepartmentChangeService;
import com.brainserve.appointment.rolechange.domain.RoleDepartmentChangeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/role-department-changes")
public class RoleDepartmentChangeController {
    private final RoleDepartmentChangeService service;
    public RoleDepartmentChangeController(RoleDepartmentChangeService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN','TEAM_LEAD')")
    RoleDepartmentChangeService.View request(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody ChangeRequest request) {
        return service.request(actor(jwt), new RoleDepartmentChangeService.Command(request.targetDepartmentId(),
                request.reason(), request.phoneNumber(), request.designation(), request.joiningDate()));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('HR_ADMIN','TEAM_LEAD')")
    List<RoleDepartmentChangeService.View> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(actor(jwt));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN')")
    List<RoleDepartmentChangeService.View> pending(@AuthenticationPrincipal Jwt jwt) {
        return service.pendingForApprover(actor(jwt));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN')")
    RoleDepartmentChangeService.View approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                             @Valid @RequestBody DecisionRequest request) {
        return service.approve(actor(jwt), id, request.resolution(), request.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN')")
    RoleDepartmentChangeService.View reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @Valid @RequestBody RejectRequest request) {
        return service.reject(actor(jwt), id, request.note());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('HR_ADMIN','TEAM_LEAD')")
    RoleDepartmentChangeService.View cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.cancel(actor(jwt), id);
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record ChangeRequest(@NotNull UUID targetDepartmentId,
                                @NotBlank @Size(min = 5, max = 500) String reason,
                                @Size(max = 30) String phoneNumber,
                                @Size(max = 120) String designation,
                                LocalDate joiningDate) {}
    public record DecisionRequest(RoleDepartmentChangeRequest.Resolution resolution,
                                  @Size(max = 500) String note) {}
    public record RejectRequest(@NotBlank @Size(min = 5, max = 500) String note) {}
}
