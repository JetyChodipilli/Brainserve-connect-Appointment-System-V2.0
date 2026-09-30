package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.PermissionAdministrationService;
import com.brainserve.appointment.iam.domain.Permission;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyAuthority('ROLE_MANAGE','STAFF_ACCOUNT_MANAGE')")
public class PermissionAdministrationController {
    private final PermissionAdministrationService service;
    public PermissionAdministrationController(PermissionAdministrationService service) { this.service = service; }

    @GetMapping("/permissions")
    List<String> permissions() { return Arrays.stream(Permission.values()).map(Enum::name).sorted().toList(); }

    @GetMapping("/roles")
    List<RoleDefinition> roles() {
        return Arrays.stream(SystemRole.values()).map(role -> new RoleDefinition(role.name(),
                role.permissions().stream().map(Enum::name).sorted().toList())).toList();
    }

    @PutMapping("/users/{userId}/permissions")
    UserPermissionResponse replace(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody PermissionOverrideRequest request) {
        UserAccount user = service.replaceOverrides(UUID.fromString(jwt.getSubject()), userId, request.grants(), request.denies());
        return UserPermissionResponse.from(user);
    }

    @GetMapping("/users/{userId}/permissions")
    UserPermissionResponse details(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return UserPermissionResponse.from(service.permissionDetails(UUID.fromString(jwt.getSubject()), userId));
    }

    public record PermissionOverrideRequest(@NotNull Set<Permission> grants, @NotNull Set<Permission> denies) {}
    public record RoleDefinition(String role, List<String> defaultPermissions) {}
    public record UserPermissionResponse(UUID userId, Set<Permission> grantedOverrides, Set<Permission> deniedOverrides,
                                         Set<Permission> effectivePermissions) {
        static UserPermissionResponse from(UserAccount user) { return new UserPermissionResponse(user.getId(),
                user.getGrantedPermissions(), user.getDeniedPermissions(), user.effectivePermissions()); }
    }
}
