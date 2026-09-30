package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.AccountProvisioningService;
import com.brainserve.appointment.iam.application.PrivilegedHrOnboardingService;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/admin/users", "/api/v1/admin/users"})
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminAccountController {

    private final AccountProvisioningService service;
    private final PrivilegedHrOnboardingService hrOnboarding;

    public SystemAdminAccountController(
            AccountProvisioningService service,
            PrivilegedHrOnboardingService hrOnboarding
    ) {
        this.service = service;
        this.hrOnboarding = hrOnboarding;
    }

    @GetMapping
    List<AccountResponse> pending(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.pendingSystemAdminApproval(actorId(jwt))
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/ceo-slot")
    AccountProvisioningService.CeoSlotView ceoSlot(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.ceoSlot(actorId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePrivilegedAccountRequest request
    ) {
        return AccountResponse.from(
                service.createPrivileged(
                        actorId(jwt),
                        request.fullName(),
                        request.email(),
                        request.role()
                )
        );
    }

    @PostMapping("/{id}/approve")
    AccountResponse approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return AccountResponse.from(
                hrOnboarding.approveBySystemAdmin(
                        actorId(jwt),
                        id
                )
        );
    }

    @PostMapping("/{id}/reject")
    AccountResponse reject(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RejectAccountRequest request
    ) {
        return AccountResponse.from(
                service.rejectBySystemAdmin(
                        actorId(jwt),
                        id,
                        request.reason()
                )
        );
    }

    private UUID actorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record CreatePrivilegedAccountRequest(
            @NotBlank
            @Size(min = 2, max = 170)
            String fullName,

            @NotBlank
            @Email
            @Size(max = 180)
            String email,

            @NotNull
            SystemRole role
    ) {
    }

    public record RejectAccountRequest(
            @Size(max = 500)
            String reason
    ) {
    }

    public record AccountResponse(
            UUID id,
            String fullName,
            String email,
            SystemRole role,
            AccountStatus status,
            UUID employeeId,
            UUID createdByUserId,
            UUID approvedByUserId,
            Instant createdAt,
            Instant approvedAt,
            UUID rejectedByUserId,
            Instant rejectedAt
    ) {
        static AccountResponse from(UserAccount account) {
            SystemRole role = account.getRoles().size() == 1
                    ? account.getRoles().iterator().next()
                    : null;

            return new AccountResponse(
                    account.getId(),
                    account.getFullName(),
                    account.getEmail(),
                    role,
                    account.getStatus(),
                    account.getEmployeeId(),
                    account.getCreatedByUserId(),
                    account.getApprovedByUserId(),
                    account.getCreatedAt(),
                    account.getApprovedAt(),
                    account.getRejectedByUserId(),
                    account.getRejectedAt()
            );
        }
    }
}