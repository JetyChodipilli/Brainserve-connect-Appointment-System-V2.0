package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.application.PrivilegedSecurityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/auth", "/api/auth"})
public class PrivilegedSecurityController {
    private final PrivilegedSecurityService security;
    public PrivilegedSecurityController(PrivilegedSecurityService security) { this.security = security; }

    @GetMapping("/security")
    public PrivilegedSecurityService.SecurityState state(@AuthenticationPrincipal Jwt jwt) {
        return security.state(user(jwt), family(jwt), JwtService.mfaVerifiedAt(jwt));
    }
    @PostMapping("/mfa/enrollment")
    public PrivilegedSecurityService.Enrollment begin(@AuthenticationPrincipal Jwt jwt) {
        return security.beginEnrollment(user(jwt), family(jwt), JwtService.mfaVerifiedAt(jwt));
    }
    @PostMapping("/mfa/enrollment/confirm")
    public PrivilegedSecurityService.Verification confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest request) {
        return security.confirmEnrollment(user(jwt), family(jwt), JwtService.mfaVerifiedAt(jwt), request.code());
    }
    @PostMapping("/mfa/verify")
    public PrivilegedSecurityService.Verification verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest request) {
        return security.verify(user(jwt), family(jwt), request.code());
    }
    @GetMapping("/sessions")
    public PrivilegedSecurityService.SessionList list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page) {
        return security.list(user(jwt), family(jwt), JwtService.mfaVerifiedAt(jwt), page);
    }
    @DeleteMapping("/sessions/{familyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        security.revoke(user(jwt), family(jwt), JwtService.mfaVerifiedAt(jwt), familyId);
    }
    private UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private UUID family(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("sid")); }
    public record CodeRequest(@NotBlank @Size(max = 64) String code) {}
}
