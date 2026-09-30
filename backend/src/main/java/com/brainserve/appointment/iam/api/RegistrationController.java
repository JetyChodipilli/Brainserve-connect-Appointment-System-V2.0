package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.AccountProvisioningService;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping({"/api/register", "/api/v1/register"})
public class RegistrationController {
    private final AccountProvisioningService service;

    public RegistrationController(AccountProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RegistrationResponse register(@Valid @RequestBody RegistrationRequest request) {
        var account = service.register(request.fullName(), request.email(), request.password(), request.role());
        String message = switch (request.role()) {
            case ROLE_HR_ADMIN, ROLE_MANAGER -> "Registration submitted to the company CEO for approval";
            case ROLE_EMPLOYEE, ROLE_RECEPTIONIST, ROLE_SECURITY ->
                    "Registration submitted for HR Admin approval";
            default -> "Registration submitted for approval";
        };
        return new RegistrationResponse(account.getId(), account.getEmail(), account.getStatus(), message);
    }

    public record RegistrationRequest(
            @NotBlank @Size(min = 2, max = 170) String fullName,
            @NotBlank @Email @Size(max = 180) String email,
            @NotBlank @Size(min = 12, max = 64) String password,
            @NotNull SystemRole role) {}
    public record RegistrationResponse(UUID id, String email, AccountStatus status, String message) {}
}
