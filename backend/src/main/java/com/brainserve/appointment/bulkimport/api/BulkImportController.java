package com.brainserve.appointment.bulkimport.api;
import com.brainserve.appointment.bulkimport.application.BulkImportService;
import com.brainserve.appointment.bulkimport.application.BulkImportService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/bulk-imports")
public class BulkImportController {
    private final BulkImportService imports;
    public BulkImportController(BulkImportService imports) {this.imports=imports;}
    @GetMapping("/options") public Options options(@AuthenticationPrincipal Jwt jwt) {return imports.options(actor(jwt));}
    @GetMapping("/templates/{kind}") public Template template(@AuthenticationPrincipal Jwt jwt,@PathVariable ImportKind kind) {return imports.template(actor(jwt),kind);}
    @PostMapping("/preview") public ImportJob preview(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Preview request) {return imports.preview(actor(jwt),request.kind(),request.duplicatePolicy(),request.csv());}
    @PostMapping("/{id}/execute") public ImportJob execute(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Execution request) {return imports.execute(actor(jwt),id,request.checksum(),request.idempotencyKey());}
    @GetMapping("/{id}") public ImportJob get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return imports.get(actor(jwt),id);}
    @GetMapping("/{id}/errors") public ErrorExport errors(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return imports.errors(actor(jwt),id);}
    private static UUID actor(Jwt jwt) {return UUID.fromString(jwt.getSubject());}
    public record Preview(@NotNull ImportKind kind,@NotNull DuplicatePolicy duplicatePolicy,@NotBlank @Size(max=2097152) String csv) {}
    public record Execution(@NotBlank @Size(min=64,max=64) String checksum,@NotBlank @Size(min=8,max=100) String idempotencyKey) {}
}
