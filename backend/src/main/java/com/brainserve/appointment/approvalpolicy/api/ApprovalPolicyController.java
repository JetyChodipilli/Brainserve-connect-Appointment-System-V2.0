package com.brainserve.appointment.approvalpolicy.api;

import com.brainserve.appointment.approvalpolicy.application.ApprovalPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approval-policies")
@PreAuthorize("isAuthenticated()")
public class ApprovalPolicyController {
    private final ApprovalPolicyService service;
    public ApprovalPolicyController(ApprovalPolicyService service) {this.service=service;}
    private <T> ResponseEntity<T> privateResponse(T value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ResponseEntity<?> policies(@AuthenticationPrincipal Jwt jwt) {return privateResponse(service.policies(actor(jwt)));}
    @PostMapping @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ResponseEntity<?> save(@AuthenticationPrincipal Jwt jwt,@RequestBody ApprovalPolicyService.Policy value) {return privateResponse(service.savePolicy(actor(jwt),value));}
    @GetMapping("/queue")
    ResponseEntity<?> queue(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="false") boolean overdue,
                           @RequestParam(defaultValue="0") int page) {return privateResponse(service.queue(actor(jwt),overdue,page));}
    @GetMapping("/stages/{id}/candidates")
    ResponseEntity<?> candidates(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return privateResponse(service.candidates(actor(jwt),id));}
    @PostMapping("/stages/{id}/delegations")
    ResponseEntity<?> delegate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody DelegateRequest value) {
        return privateResponse(service.delegate(actor(jwt),id,value.delegateId(),value.expiresAt(),value.reason()));
    }
    @DeleteMapping("/delegations/{id}")
    ResponseEntity<?> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {service.revoke(actor(jwt),id);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    public record DelegateRequest(@NotNull UUID delegateId,@NotNull Instant expiresAt,@NotBlank @Size(min=5,max=500) String reason) {}
    private UUID actor(Jwt jwt) {return UUID.fromString(jwt.getSubject());}
}
