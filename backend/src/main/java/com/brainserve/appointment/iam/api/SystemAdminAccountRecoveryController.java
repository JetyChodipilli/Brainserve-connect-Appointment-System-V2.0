package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.AccountRecoveryService;
import com.brainserve.appointment.iam.domain.AccountRecoveryRequest;
import com.brainserve.appointment.iam.domain.AccountRecoveryStatus;
import com.brainserve.appointment.iam.domain.AccountRecoveryType;
import com.brainserve.appointment.iam.domain.SystemRole;
import jakarta.validation.Valid;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/admin/account-recovery", "/api/admin/account-recovery"})
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminAccountRecoveryController {
    private final AccountRecoveryService service;

    public SystemAdminAccountRecoveryController(AccountRecoveryService service) { this.service = service; }

    @GetMapping
    List<RecoveryResponse> pending() {
        return service.pending().stream().map(request -> RecoveryResponse.from(request, null)).toList();
    }

    @PostMapping("/{id}/approve")
    RecoveryResponse approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        AccountRecoveryService.Approval approval = service.approve(actorId(jwt), id);
        return RecoveryResponse.from(approval.request(), approval.recoveryCode());
    }

    @PostMapping("/{id}/reject")
    RecoveryResponse reject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                            @Valid @RequestBody RejectRecoveryRequest request) {
        return RecoveryResponse.from(service.reject(actorId(jwt), id, request.reason()), null);
    }

    private UUID actorId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record RejectRecoveryRequest(@Size(max = 500) String reason) {}
    public record RecoveryResponse(UUID id, UUID userId, String fullName, String email, SystemRole role,
                                   AccountRecoveryType type, AccountRecoveryStatus status, Instant requestedAt,
                                   Instant approvedAt, Instant expiresAt, String recoveryCode) {
        static RecoveryResponse from(AccountRecoveryRequest request, String code) {
            SystemRole role = displayRole(request.getUser().getRoles());
            return new RecoveryResponse(request.getId(), request.getUser().getId(), request.getUser().getFullName(),
                    request.getUser().getEmail(), role, request.getType(), request.getStatus(),
                    request.getCreatedAt(), request.getApprovedAt(), request.getExpiresAt(), code);
        }

        private static SystemRole displayRole(java.util.Set<SystemRole> roles) {
            return java.util.stream.Stream.of(
                            SystemRole.ROLE_SYSTEM_ADMIN,
                            SystemRole.ROLE_CEO,
                            SystemRole.ROLE_MANAGER,
                            SystemRole.ROLE_HR_ADMIN,
                            SystemRole.ROLE_TEAM_LEAD,
                            SystemRole.ROLE_RECEPTIONIST,
                            SystemRole.ROLE_SECURITY,
                            SystemRole.ROLE_EMPLOYEE)
                    .filter(roles::contains)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Recovery account has no assigned role"));
        }
    }
}
