package com.brainserve.appointment.configuration.api;
import com.brainserve.appointment.configuration.application.CompanySetupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/company-setup")
public class CompanySetupController {
    private final CompanySetupService service;
    public CompanySetupController(CompanySetupService service) { this.service=service; }
    @GetMapping public CompanySetupService.SetupState state(@AuthenticationPrincipal Jwt jwt) { return service.state(UUID.fromString(jwt.getSubject())); }
    @PutMapping("/progress") public CompanySetupService.SetupState progress(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Progress request) { return service.progress(UUID.fromString(jwt.getSubject()),request.expectedRevision(),request.stepId()); }
    @PostMapping("/complete") public CompanySetupService.SetupState complete(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Completion request) { return service.complete(UUID.fromString(jwt.getSubject()),request.expectedRevision()); }
    public record Progress(@Min(0) long expectedRevision,@NotBlank @Size(max=30) String stepId) {}
    public record Completion(@Min(0) long expectedRevision) {}
}
