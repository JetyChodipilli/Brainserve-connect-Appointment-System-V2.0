package com.brainserve.appointment.configuration.api;

import com.brainserve.appointment.configuration.application.WorkspaceSettingsService;
import com.brainserve.appointment.configuration.domain.SystemSetting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system-settings")
@PreAuthorize("hasAuthority('SYSTEM_CONFIGURE')")
public class SystemConfigurationController {
    private final WorkspaceSettingsService settings;
    public SystemConfigurationController(WorkspaceSettingsService settings) { this.settings = settings; }

    @GetMapping
    List<SettingResponse> list() { return settings.list().stream().map(SettingResponse::from).toList(); }

    @PutMapping("/{key}")
    SettingResponse update(@PathVariable @Pattern(regexp = "[A-Z0-9_.-]{3,120}") String key,
                           @Valid @RequestBody SettingRequest request) {
        return SettingResponse.from(settings.update(key, request.value()));
    }
    public record SettingRequest(@NotBlank @Size(max = 2000) String value) {}
    public record SettingResponse(String key, String value, String type, String description, long version) {
        static SettingResponse from(SystemSetting value) { return new SettingResponse(value.getKey(), value.getValue(), value.getValueType(), value.getDescription(), value.getVersion()); }
    }
}
