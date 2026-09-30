package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.AccountRecoveryService;
import com.brainserve.appointment.iam.domain.AccountRecoveryType;
import com.brainserve.appointment.iam.domain.SystemRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/auth/recovery", "/api/auth/recovery"})
public class AccountRecoveryController {
    private final AccountRecoveryService service;

    public AccountRecoveryController(AccountRecoveryService service) { this.service = service; }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RecoveryRequestAccepted request(@Valid @RequestBody RecoveryRequest request) {
        service.request(request.identifier(), request.role(), request.type());
        return new RecoveryRequestAccepted(
                "If the active account details match, the request is waiting for System Admin approval");
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void password(@Valid @RequestBody PasswordRecovery request) {
        service.recoverPassword(request.code(), request.newPassword(), request.confirmPassword());
    }

    @PostMapping("/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void email(@Valid @RequestBody EmailRecovery request) {
        service.recoverEmail(request.code(), request.newEmail(), request.confirmEmail());
    }

    public record RecoveryRequest(
            @NotBlank @Size(min = 2, max = 180) String identifier,
            @NotNull SystemRole role,
            @NotNull AccountRecoveryType type) {}
    public record PasswordRecovery(
            @NotBlank @Pattern(regexp = "(?i)BSR-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}") String code,
            @NotBlank @Size(min = 12, max = 64) String newPassword,
            @NotBlank @Size(min = 12, max = 64) String confirmPassword) {}
    public record EmailRecovery(
            @NotBlank @Pattern(regexp = "(?i)BSR-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}") String code,
            @NotBlank @Email @Size(max = 180) String newEmail,
            @NotBlank @Email @Size(max = 180) String confirmEmail) {}
    public record RecoveryRequestAccepted(String message) {}
}
