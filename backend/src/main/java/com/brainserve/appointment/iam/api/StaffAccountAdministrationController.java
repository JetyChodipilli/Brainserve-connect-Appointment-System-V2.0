package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.StaffAccountAdministrationService;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.UserAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/staff-accounts")
@PreAuthorize("hasAuthority('STAFF_ACCOUNT_MANAGE')")
public class StaffAccountAdministrationController {
    private final StaffAccountAdministrationService service;

    public StaffAccountAdministrationController(StaffAccountAdministrationService service) { this.service = service; }

    @GetMapping
    Page<StaffAccountResponse> list(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(required = false) String query,
                                    Pageable pageable) {
        if (pageable.getPageSize() < 25 || pageable.getPageSize() > 100) {
            throw new com.brainserve.appointment.shared.application.BusinessException(
                    "INVALID_PAGE_SIZE", "Page size must be between 25 and 100",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        return service.list(actorId(jwt), query, pageable).map(StaffAccountResponse::from);
    }

    @PostMapping
    StaffAccountResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateStaffAccountRequest request) {
        return StaffAccountResponse.from(service.create(actorId(jwt), request.email(), request.temporaryPassword(), request.role()));
    }

    @PatchMapping("/{userId}/email")
    StaffAccountResponse changeEmail(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody ChangeEmailRequest request) {
        return StaffAccountResponse.from(service.changeEmail(actorId(jwt), userId, request.email()));
    }

    @PostMapping("/{userId}/reset-password")
    StaffAccountResponse resetPassword(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ResetPasswordRequest request) {
        return StaffAccountResponse.from(service.resetPassword(actorId(jwt), userId, request.temporaryPassword()));
    }

    @PatchMapping("/{userId}/status")
    StaffAccountResponse status(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt,
                                @Valid @RequestBody AccountStatusRequest request) {
        return StaffAccountResponse.from(service.setEnabled(actorId(jwt), userId, request.enabled()));
    }

    private UUID actorId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record CreateStaffAccountRequest(@NotBlank @Email @Size(max = 180) String email,
                                            @NotBlank @Size(min = 12, max = 64) String temporaryPassword,
                                            @NotNull SystemRole role) {}
    public record ChangeEmailRequest(@NotBlank @Email @Size(max = 180) String email) {}
    public record ResetPasswordRequest(@NotBlank @Size(min = 12, max = 64) String temporaryPassword) {}
    public record AccountStatusRequest(boolean enabled) {}
    public record StaffAccountResponse(UUID userId, UUID employeeId, String fullName, String email, Set<SystemRole> roles, boolean enabled,
                                       boolean forcePasswordChange, AccountStatus status,
                                       Set<com.brainserve.appointment.iam.domain.Permission> grantedPermissions,
                                       Set<com.brainserve.appointment.iam.domain.Permission> deniedPermissions,
                                       Set<com.brainserve.appointment.iam.domain.Permission> effectivePermissions) {
        static StaffAccountResponse from(UserAccount user) {
            return new StaffAccountResponse(user.getId(), user.getEmployeeId(), user.getFullName(), user.getEmail(), user.getRoles(), user.isEnabled(),
                    user.isForcePasswordChange(), user.getStatus(), user.getGrantedPermissions(),
                    user.getDeniedPermissions(), user.effectivePermissions());
        }
    }
}
