package com.brainserve.appointment.employee.api;

import com.brainserve.appointment.employee.application.EmployeeTerminationService;
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
@RequestMapping("/api/v1/employee-terminations")
public class EmployeeTerminationController {
    private final EmployeeTerminationService service;
    public EmployeeTerminationController(EmployeeTerminationService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasRole('HR_ADMIN')")
    EmployeeTerminationService.View request(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody CreateRequest request) {
        return service.request(actor(jwt), request.employeeId(), request.reason(), request.effectiveDate());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('HR_ADMIN')")
    List<EmployeeTerminationService.View> mine(@AuthenticationPrincipal Jwt jwt) { return service.mine(actor(jwt)); }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('CEO')")
    List<EmployeeTerminationService.View> pending(@AuthenticationPrincipal Jwt jwt) { return service.pending(actor(jwt)); }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('HR_ADMIN','CEO','SYSTEM_ADMIN')")
    List<EmployeeTerminationService.View> history(@AuthenticationPrincipal Jwt jwt) { return service.history(actor(jwt)); }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('CEO')")
    EmployeeTerminationService.View approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @Valid @RequestBody ApproveRequest request) {
        return service.approve(actor(jwt), id, request.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('CEO')")
    EmployeeTerminationService.View reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @Valid @RequestBody RejectRequest request) {
        return service.reject(actor(jwt), id, request.note());
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record CreateRequest(@NotNull UUID employeeId,
                                @NotBlank @Size(min = 5, max = 1000) String reason,
                                @NotNull LocalDate effectiveDate) {}
    public record ApproveRequest(@Size(max = 1000) String note) {}
    public record RejectRequest(@NotBlank @Size(min = 5, max = 1000) String note) {}
}
