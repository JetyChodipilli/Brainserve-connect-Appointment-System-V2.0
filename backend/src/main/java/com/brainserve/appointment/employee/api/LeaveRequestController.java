package com.brainserve.appointment.employee.api;

import com.brainserve.appointment.employee.application.LeaveRequestService;
import com.brainserve.appointment.employee.domain.LeaveRequest;
import com.brainserve.appointment.employee.domain.LeaveRequestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/leave-requests")
public class LeaveRequestController {
    private final LeaveRequestService service;
    public LeaveRequestController(LeaveRequestService service) { this.service = service; }
    @PostMapping @PreAuthorize("hasAuthority('LEAVE_REQUEST_CREATE')")
    LeaveResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
        return LeaveResponse.from(service.create(userId(jwt), employeeId(jwt), request.startDate(), request.endDate(), request.reason()));
    }
    @GetMapping("/me") @PreAuthorize("hasAuthority('LEAVE_REQUEST_CREATE')")
    List<LeaveResponse> mine(@AuthenticationPrincipal Jwt jwt) { return service.mine(employeeId(jwt)).stream().map(LeaveResponse::from).toList(); }
    @GetMapping("/pending") @PreAuthorize("hasAuthority('LEAVE_REQUEST_REVIEW')")
    List<LeaveResponse> pending(@AuthenticationPrincipal Jwt jwt) {
        return service.pending(userId(jwt)).stream().map(LeaveResponse::from).toList();
    }
    @PostMapping("/{id}/{decision}") @PreAuthorize("hasAuthority('LEAVE_REQUEST_REVIEW')")
    LeaveResponse decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable String decision,
                         @Valid @RequestBody DecisionRequest request) {
        LeaveRequestStatus status = "approve".equalsIgnoreCase(decision) ? LeaveRequestStatus.APPROVED
                : "reject".equalsIgnoreCase(decision) ? LeaveRequestStatus.REJECTED
                : throwBadDecision();
        return LeaveResponse.from(service.decide(id, userId(jwt), status, request.remarks()));
    }
    private static LeaveRequestStatus throwBadDecision() {
        throw new com.brainserve.appointment.shared.application.BusinessException(
                "INVALID_LEAVE_DECISION", "Decision must be approve or reject",
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private UUID userId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private UUID employeeId(Jwt jwt) {
        String employeeId = jwt.getClaimAsString("employeeId");
        if (employeeId == null) throw new com.brainserve.appointment.shared.application.BusinessException(
                "EMPLOYEE_PROFILE_NOT_LINKED", "This login is not linked to an employee profile",
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        return UUID.fromString(employeeId);
    }
    public record CreateRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                @NotBlank @Size(min=5,max=1000) String reason) {}
    public record DecisionRequest(@Size(max=500) String remarks) {}
    public record LeaveResponse(UUID id, UUID employeeId, UUID requesterUserId, LocalDate startDate, LocalDate endDate,
                                String reason, LeaveRequestStatus status, UUID decidedByUserId, Instant decidedAt,
                                String decisionReason, Instant createdAt) {
        static LeaveResponse from(LeaveRequest value) { return new LeaveResponse(value.getId(), value.getEmployeeId(),
                value.getRequesterUserId(), value.getStartDate(), value.getEndDate(), value.getReason(), value.getStatus(),
                value.getDecidedByUserId(), value.getDecidedAt(), value.getDecisionReason(), value.getCreatedAt()); }
    }
}
