package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.AccountProvisioningService;
import com.brainserve.appointment.iam.application.PrivilegedHrOnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
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
@RequestMapping({"/api/ceo/users", "/api/v1/ceo/users"})
@PreAuthorize("hasRole('CEO')")
public class CeoAccountApprovalController {

    private final AccountProvisioningService service;
    private final PrivilegedHrOnboardingService hrOnboarding;

    public CeoAccountApprovalController(
            AccountProvisioningService service,
            PrivilegedHrOnboardingService hrOnboarding
    ) {
        this.service = service;
        this.hrOnboarding = hrOnboarding;
    }

    @GetMapping
    List<SystemAdminAccountController.AccountResponse> pending(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.pendingCeoApproval(actorId(jwt))
                .stream()
                .map(SystemAdminAccountController.AccountResponse::from)
                .toList();
    }

    @PostMapping("/{id}/approve")
    SystemAdminAccountController.AccountResponse approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PrivilegedApprovalRequest request
    ) {
        var approved = hrOnboarding.approveByCeo(
                actorId(jwt),
                id,
                request.command()
        );

        return SystemAdminAccountController.AccountResponse.from(approved);
    }

    @PostMapping("/{id}/reject")
    SystemAdminAccountController.AccountResponse reject(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody
            SystemAdminAccountController.RejectAccountRequest request
    ) {
        return SystemAdminAccountController.AccountResponse.from(
                service.rejectByCeo(
                        actorId(jwt),
                        id,
                        request.reason()
                )
        );
    }

    private UUID actorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record PrivilegedApprovalRequest(
            @NotNull
            UUID departmentId,

            @Size(max = 30)
            String phoneNumber,

            @NotBlank
            @Size(max = 120)
            String designation,

            @NotNull
            @PastOrPresent
            LocalDate joiningDate
    ) {
        PrivilegedHrOnboardingService.HrOnboardingCommand command() {
            return new PrivilegedHrOnboardingService.HrOnboardingCommand(
                    departmentId,
                    phoneNumber,
                    designation,
                    joiningDate
            );
        }
    }
}