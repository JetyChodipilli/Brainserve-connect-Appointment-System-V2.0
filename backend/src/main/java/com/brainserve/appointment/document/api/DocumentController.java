package com.brainserve.appointment.document.api;

import com.brainserve.appointment.document.application.DocumentService;
import com.brainserve.appointment.document.domain.StoredDocument;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EMPLOYEE_DOCUMENT_WRITE')")
    DocumentResponse upload(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam @Pattern(regexp = "EMPLOYEE|VISITOR") String ownerType,
                            @RequestParam UUID ownerId,
                            @RequestParam @Pattern(regexp = "PHOTO|IDENTITY|EMPLOYMENT|OTHER") String category,
                            @RequestParam MultipartFile file) {
        return DocumentResponse.from(service.upload(actorId(jwt), ownerType, ownerId, category, file));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EMPLOYEE_DOCUMENT_READ')")
    List<DocumentResponse> list(@AuthenticationPrincipal Jwt jwt,
                                @RequestParam @Pattern(regexp = "EMPLOYEE|VISITOR") String ownerType,
                                @RequestParam UUID ownerId) {
        return service.list(actorId(jwt), ownerType, ownerId).stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/{id}/download-url")
    @PreAuthorize("hasAuthority('EMPLOYEE_DOCUMENT_READ')")
    DownloadResponse download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return new DownloadResponse(service.createDownloadUrl(actorId(jwt), id), Instant.now().plusSeconds(300));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('EMPLOYEE_DOCUMENT_WRITE')")
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { service.delete(actorId(jwt), id); }

    private UUID actorId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record DocumentResponse(UUID id, String ownerType, UUID ownerId, String category, String filename,
                                   String contentType, long sizeBytes, String sha256, String status, Instant createdAt) {
        static DocumentResponse from(StoredDocument value) { return new DocumentResponse(value.getId(), value.getOwnerType(),
                value.getOwnerId(), value.getCategory(), value.getOriginalFilename(), value.getContentType(), value.getSizeBytes(),
                value.getSha256(), value.getStatus().name(), value.getCreatedAt()); }
    }
    public record DownloadResponse(String url, Instant expiresAt) {}
}
