package com.brainserve.appointment.configuration.api;

import com.brainserve.appointment.configuration.application.WorkspaceSettingsService;
import com.brainserve.appointment.configuration.domain.SystemSetting;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspace-settings")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','CEO','HR_ADMIN')")
public class WorkspaceSettingsController {
    private final WorkspaceSettingsService service;

    public WorkspaceSettingsController(WorkspaceSettingsService service) { this.service = service; }

    @GetMapping
    List<SettingResponse> list() { return service.list().stream().map(SettingResponse::from).toList(); }

    @PutMapping("/{key}")
    SettingResponse update(@PathVariable @Pattern(regexp = "[A-Z0-9_.-]{3,120}") String key,
                           @Valid @RequestBody SettingRequest request, Authentication authentication) {
        requireManagementAuthority(key, authentication);
        return SettingResponse.from(service.update(key, request.value()));
    }

    private void requireManagementAuthority(String key, Authentication authentication) {
        if (has(authentication, "SYSTEM_CONFIGURE")) return;
        String required = key.startsWith("COMPANY.") ? "COMPANY_PROFILE_MANAGE"
                : key.startsWith("APPOINTMENT.") || key.startsWith("APPROVAL.") ? "APPOINTMENT_POLICY_MANAGE"
                : key.startsWith("NOTIFICATION.") ? "NOTIFICATION_CONFIGURE"
                : key.startsWith("PRIVACY.") ? "PRIVACY_POLICY_MANAGE"
                : "SYSTEM_CONFIGURE";
        if (!has(authentication, required)) {
            throw new BusinessException("SETTING_SCOPE_DENIED", "Your role cannot change this setting", HttpStatus.FORBIDDEN);
        }
    }

    private boolean has(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(value -> value.getAuthority().equals(authority));
    }

    public record SettingRequest(@NotBlank @Size(max = 2000) String value) {}
    public record SettingResponse(String key, String value, String type, String description, long version) {
        static SettingResponse from(SystemSetting value) {
            return new SettingResponse(value.getKey(), value.getValue(), value.getValueType(), value.getDescription(), value.getVersion());
        }
    }
}
