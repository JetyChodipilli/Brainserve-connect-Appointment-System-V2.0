package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.AccountClosureService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/account-closures")
@PreAuthorize("isAuthenticated()")
public class AccountClosureController {
    private final AccountClosureService service;
    public AccountClosureController(AccountClosureService service) { this.service = service; }

    @PostMapping("/me")
    @PreAuthorize("hasAnyRole('CEO','MANAGER','HR_ADMIN','TEAM_LEAD','RECEPTIONIST','SECURITY')")
    AccountClosureService.View request(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody CreateRequest request) {
        return service.requestSelf(actor(jwt), request.reason(), request.effectiveDate(), request.replacementUserId());
    }

    @GetMapping("/me")
    List<AccountClosureService.View> mine(@AuthenticationPrincipal Jwt jwt) { return service.mine(actor(jwt)); }

    @GetMapping("/business-pending")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN')")
    List<AccountClosureService.View> businessPending(@AuthenticationPrincipal Jwt jwt) {
        return service.businessPending(actor(jwt));
    }

    @PostMapping("/{id}/business-approve")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN')")
    AccountClosureService.View businessApprove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @Valid @RequestBody ApprovalRequest request) {
        return service.businessApprove(actor(jwt), id, request.replacementUserId(), request.note());
    }

    @PostMapping("/{id}/business-reject")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN')")
    AccountClosureService.View businessReject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                              @Valid @RequestBody RejectionRequest request) {
        return service.businessReject(actor(jwt), id, request.note());
    }

    @PostMapping("/{id}/cancel")
    AccountClosureService.View cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.cancel(actor(jwt), id);
    }

    @GetMapping("/candidates")
    List<AccountClosureService.Candidate> candidates(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestParam UUID targetUserId) {
        return service.candidates(actor(jwt), targetUserId);
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record CreateRequest(@NotBlank @Size(min = 5, max = 1000) String reason,
                                @NotNull @FutureOrPresent LocalDate effectiveDate, UUID replacementUserId) {}
    public record ApprovalRequest(UUID replacementUserId, @Size(max = 1000) String note) {}
    public record RejectionRequest(@NotBlank @Size(min = 5, max = 1000) String note) {}
}
