package com.brainserve.appointment.support.api;

import com.brainserve.appointment.support.application.DiagnosticSnapshot;
import com.brainserve.appointment.support.application.SupportDiagnosticService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/support/diagnostics")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SupportDiagnosticController {
    private final SupportDiagnosticService service;
    public SupportDiagnosticController(SupportDiagnosticService service) { this.service = service; }

    @GetMapping("/preview")
    public ResponseEntity<DiagnosticSnapshot> preview(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(actor(jwt), hours));
    }
    @PostMapping
    public ResponseEntity<SupportDiagnosticService.Package> generate(@AuthenticationPrincipal Jwt jwt,
            @RequestBody SupportDiagnosticService.Generate request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.generate(actor(jwt), request));
    }
    @GetMapping
    public ResponseEntity<List<SupportDiagnosticService.Package>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor(jwt)));
    }
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var download = service.download(actor(jwt), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("brainserve-diagnostics-" + download.id() + ".json").build().toString())
                .body(download.content());
    }
    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
