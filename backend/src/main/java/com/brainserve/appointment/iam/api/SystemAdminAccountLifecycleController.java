package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.AccountClosureService;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/account-closures")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminAccountLifecycleController {
    private final AccountClosureService service;
    public SystemAdminAccountLifecycleController(AccountClosureService service) { this.service = service; }

    @GetMapping
    List<AccountClosureService.View> requests(@AuthenticationPrincipal Jwt jwt) {
        return service.systemAdminRequests(actor(jwt));
    }

    @GetMapping("/active-accounts")
    Page<AccountClosureService.AccountView> activeAccounts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) SystemRole role,
            @RequestParam(required = false) UUID departmentId,
            @PageableDefault(size = 25) Pageable pageable) {
        return service.activeAccounts(actor(jwt), query, role, departmentId,
                bounded(pageable, Sort.by(Sort.Direction.ASC, "fullName")));
    }

    @GetMapping("/archived")
    Page<AccountClosureService.ArchivedView> archivedAccounts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String query,
            @PageableDefault(size = 25) Pageable pageable) {
        return service.archivedAccounts(actor(jwt), query,
                bounded(pageable, Sort.by(Sort.Direction.DESC, "archivedAt")));
    }

    @GetMapping("/{id}/history")
    List<AccountClosureService.LifecycleView> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.lifecycle(actor(jwt), id);
    }

    @PostMapping("/{id}/approve")
    AccountClosureService.View approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody ApprovalRequest request) {
        return service.systemAdminApprove(actor(jwt), id, request.replacementUserId(), request.note());
    }

    @PostMapping("/{id}/reject")
    AccountClosureService.View reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                      @Valid @RequestBody RejectionRequest request) {
        return service.systemAdminReject(actor(jwt), id, request.note());
    }

    @PostMapping("/direct-archive/request-otp")
    AccountClosureService.DirectArchiveChallengeView requestOtp(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DirectOtpRequest request) {
        return service.requestDirectArchiveOtp(actor(jwt), request.targetUserId(), request.currentPassword(),
                request.reason(), request.replacementUserId());
    }

    @GetMapping("/direct-archive/challenge")
    ResponseEntity<AccountClosureService.DirectArchiveChallengeView> activeChallenge(
            @AuthenticationPrincipal Jwt jwt) {
        AccountClosureService.DirectArchiveChallengeView challenge =
                service.activeDirectArchiveChallenge(actor(jwt));
        return challenge == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(challenge);
    }

    @PostMapping("/direct-archive/challenge/{challengeId}/resend")
    AccountClosureService.DirectArchiveChallengeView resendOtp(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID challengeId) {
        return service.resendDirectArchiveOtp(actor(jwt), challengeId);
    }

    @DeleteMapping("/direct-archive/challenge/{challengeId}")
    void cancelChallenge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID challengeId) {
        service.cancelDirectArchiveChallenge(actor(jwt), challengeId);
    }

    @PostMapping("/direct-archive")
    AccountClosureService.View directArchive(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody DirectArchiveRequest request) {
        return service.directArchive(actor(jwt), request.challengeId(), request.otp());
    }

    @PostMapping("/archived-recovery/request-otp")
    AccountClosureService.ArchivedRecoveryChallengeView requestRecoveryOtp(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ArchivedRecoveryOtpRequest request) {
        return service.requestArchivedRecoveryOtp(actor(jwt), request.archivedAccountId(),
                request.targetRole(), request.departmentId(), request.currentPassword(), request.reason());
    }

    @GetMapping("/archived-recovery/challenge")
    ResponseEntity<AccountClosureService.ArchivedRecoveryChallengeView> activeRecoveryChallenge(
            @AuthenticationPrincipal Jwt jwt) {
        AccountClosureService.ArchivedRecoveryChallengeView challenge =
                service.activeArchivedRecoveryChallenge(actor(jwt));
        return challenge == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(challenge);
    }

    @PostMapping("/archived-recovery/challenge/{challengeId}/resend")
    AccountClosureService.ArchivedRecoveryChallengeView resendRecoveryOtp(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID challengeId) {
        return service.resendArchivedRecoveryOtp(actor(jwt), challengeId);
    }

    @DeleteMapping("/archived-recovery/challenge/{challengeId}")
    void cancelRecoveryChallenge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID challengeId) {
        service.cancelArchivedRecoveryChallenge(actor(jwt), challengeId);
    }

    @PostMapping("/archived-recovery")
    AccountClosureService.RecoveredAccountView recoverArchived(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ArchivedRecoveryRequest request) {
        return service.recoverArchivedAccount(actor(jwt), request.challengeId(), request.otp());
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private Pageable bounded(Pageable pageable, Sort sort) {
        if (pageable.getPageSize() < 25 || pageable.getPageSize() > 100) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Page size must be between 25 and 100",
                    HttpStatus.BAD_REQUEST);
        }
        return PageRequest.of(Math.max(0, pageable.getPageNumber()), pageable.getPageSize(), sort);
    }
    public record ApprovalRequest(UUID replacementUserId, @Size(max = 1000) String note) {}
    public record RejectionRequest(@NotBlank @Size(min = 5, max = 1000) String note) {}
    public record DirectOtpRequest(@NotNull UUID targetUserId,
                                   @NotBlank @Size(max = 128) String currentPassword,
                                   @NotBlank @Size(min = 5, max = 1000) String reason,
                                   UUID replacementUserId) {}
    public record DirectArchiveRequest(@NotNull UUID challengeId,
                                       @NotBlank @Pattern(regexp = "\\d{6}") String otp) {}
    public record ArchivedRecoveryOtpRequest(
            @NotNull UUID archivedAccountId, @NotNull SystemRole targetRole,
            UUID departmentId, @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 5, max = 1000) String reason) {}
    public record ArchivedRecoveryRequest(@NotNull UUID challengeId,
                                          @NotBlank @Pattern(regexp = "\\d{6}") String otp) {}
}
