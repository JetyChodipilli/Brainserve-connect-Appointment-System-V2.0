package com.brainserve.appointment.visitor.api;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.visitor.domain.Visitor;
import com.brainserve.appointment.visitor.infrastructure.VisitorRepository;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class VisitorController {
    private final VisitorRepository visitors;
    private final AuditService audit;
    private final WorkspacePolicy workspacePolicy;
    public VisitorController(VisitorRepository visitors, AuditService audit, WorkspacePolicy workspacePolicy) {
        this.visitors = visitors; this.audit = audit; this.workspacePolicy = workspacePolicy;
    }

    @PostMapping("/public/visitors")
    @Transactional
    VisitorResponse register(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody VisitorRequest request) {
        Visitor existing = visitors.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) return VisitorResponse.from(existing);
        String activeConsentVersion = workspacePolicy.stringValue("PRIVACY.CONSENT_VERSION", "2026.1");
        if (!activeConsentVersion.equals(request.consentVersion())) {
            throw new BusinessException("CONSENT_VERSION_OUTDATED",
                    "Refresh the privacy notice and consent to the current version", HttpStatus.CONFLICT);
        }
        Visitor visitor = visitors.save(new Visitor(idempotencyKey, request.name(), request.email(),
                request.phone(), request.company(), request.governmentId(), activeConsentVersion));
        audit.record("VISITOR_REGISTERED", "VISITOR", visitor.getId().toString(), "{\"registered\":true}");
        return VisitorResponse.from(visitor);
    }

    @GetMapping("/visitors/{id}")
    @PreAuthorize("hasAuthority('VISITOR_VERIFY')")
    VisitorResponse get(@PathVariable UUID id) { return VisitorResponse.from(visitors.findById(id).orElseThrow(this::notFound)); }

    @GetMapping("/visitors/search")
    @PreAuthorize("hasAuthority('VISITOR_VERIFY')")
    Page<VisitorResponse> search(@RequestParam String query, Pageable pageable) {
        return visitors.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(query, query, pageable).map(VisitorResponse::from);
    }

    @PostMapping("/visitors/{id}/verify")
    @PreAuthorize("hasAuthority('VISITOR_VERIFY')")
    @Transactional
    VisitorResponse verify(@PathVariable UUID id) {
        Visitor visitor = visitors.findById(id).orElseThrow(this::notFound); visitor.verify();
        audit.record("VISITOR_VERIFY", "VISITOR", id.toString(), "{\"verified\":true}");
        return VisitorResponse.from(visitor);
    }

    private BusinessException notFound() { return new BusinessException("VISITOR_NOT_FOUND", "Visitor was not found", HttpStatus.NOT_FOUND); }
    public record VisitorRequest(@NotBlank @Size(max = 170) String name, @NotBlank @Email @Size(max = 180) String email,
                                 @NotBlank @Size(max = 30) @Pattern(regexp = "\\+?[0-9 ()-]{8,30}") String phone,
                                 @Size(max = 160) String company,
                                 @Size(max = 120) String governmentId,
                                 @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,40}") String consentVersion) {}
    public record VisitorResponse(UUID id, String name, String email, String phone, String company,
                                  String governmentIdMasked, boolean identityVerified, String consentVersion,
                                  java.time.Instant consentedAt, boolean restricted) {
        static VisitorResponse from(Visitor value) { return new VisitorResponse(value.getId(), value.getName(), value.getEmail(),
                value.getPhone(), value.getCompany(), value.getGovernmentIdLast4() == null ? null : "****" + value.getGovernmentIdLast4(),
                value.isIdentityVerified(), value.getConsentVersion(), value.getConsentedAt(), value.isRestricted()); }
    }
}
