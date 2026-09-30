package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.OperationalRoleTransitionService;
import com.brainserve.appointment.iam.domain.SystemRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/admin/role-transitions")
public class OperationalRoleTransitionController {
    private final OperationalRoleTransitionService service;

    public OperationalRoleTransitionController(OperationalRoleTransitionService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('CEO','SYSTEM_ADMIN')")
    Page<OperationalRoleTransitionService.Candidate> candidates(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String query,
            Pageable pageable) {
        if (pageable.getPageSize() < 25 || pageable.getPageSize() > 100) {
            throw new com.brainserve.appointment.shared.application.BusinessException(
                    "INVALID_PAGE_SIZE", "Page size must be between 25 and 100",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        return service.candidates(UUID.fromString(jwt.getSubject()), query, pageable);
    }

    @PostMapping("/{userId}")
    @PreAuthorize("hasAnyRole('CEO','SYSTEM_ADMIN')")
    OperationalRoleTransitionService.Result transition(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @Valid @RequestBody Request request) {
        return service.transition(UUID.fromString(jwt.getSubject()), userId, request.role(),
                request.departmentId(), request.reason());
    }

    @PostMapping("/ceo-succession")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    OperationalRoleTransitionService.SuccessionResult succeedChiefExecutive(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CeoSuccessionRequest request) {
        return service.succeedChiefExecutive(UUID.fromString(jwt.getSubject()), request.currentCeoUserId(),
                request.successorUserId(), request.formerCeoDepartmentId(), request.reason());
    }

    public record Request(@NotNull SystemRole role, @NotNull UUID departmentId,
                          @NotBlank @Size(max = 500) String reason) {}
    public record CeoSuccessionRequest(@NotNull UUID currentCeoUserId, @NotNull UUID successorUserId,
                                       @NotNull UUID formerCeoDepartmentId,
                                       @NotBlank @Size(max = 500) String reason) {}
}
