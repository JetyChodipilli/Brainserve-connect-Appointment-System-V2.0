package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.AuthenticationService;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/auth", "/api/auth"})
public class AuthController {
    private final AuthenticationService authentication;
    private final UserAccountRepository users;

    public AuthController(AuthenticationService authentication, UserAccountRepository users) {
        this.authentication = authentication; this.users = users;
    }

    @PostMapping("/login")
    AuthenticationService.TokenPair login(@Valid @RequestBody LoginRequest request) {
        return authentication.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    AuthenticationService.TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
        return authentication.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) { authentication.logout(request.refreshToken()); }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutAll(@AuthenticationPrincipal Jwt jwt) { authentication.logoutAll(UUID.fromString(jwt.getSubject())); }

    @PostMapping("/change-password/request-otp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void requestPasswordChangeOtp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeOtpRequest request) {
        authentication.requestPasswordChangeOtp(UUID.fromString(jwt.getSubject()), request.currentPassword());
    }

    @PostMapping("/change-password/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmPasswordChange(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeConfirmRequest request) {
        authentication.confirmPasswordChange(UUID.fromString(jwt.getSubject()), request.otp(), request.newPassword());
    }

    @PostMapping("/change-email")
    void changeEmail(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangeEmailRequest request) {
        authentication.changeEmail(UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newEmail());
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        var user = users.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        Set<String> roles = user.getRoles().stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> permissions = user.effectivePermissions().stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new MeResponse(user.getId(), user.getEmployeeId(), user.getEmail(), roles, permissions, user.isForcePasswordChange());
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 180) String email,
                               @NotBlank @Size(min = 8, max = 128) String password) {}
    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {}
    public record PasswordChangeOtpRequest(@NotBlank @Size(max = 128) String currentPassword) {}
    public record PasswordChangeConfirmRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "\\d{6}") String otp,
            @NotBlank @Size(min = 12, max = 64) String newPassword) {}
    public record ChangeEmailRequest(@NotBlank @Size(max = 128) String currentPassword,
                                     @NotBlank @Email @Size(max = 180) String newEmail) {}
    public record MeResponse(UUID userId, UUID employeeId, String email, Set<String> roles, Set<String> permissions,
                             boolean forcePasswordChange) {}
}
