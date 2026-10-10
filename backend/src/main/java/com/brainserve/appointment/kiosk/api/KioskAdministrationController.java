package com.brainserve.appointment.kiosk.api;
import com.brainserve.appointment.kiosk.application.KioskService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1")
public class KioskAdministrationController {
    private final KioskService service;
    public KioskAdministrationController(KioskService service) {this.service=service;}
    private <T> ResponseEntity<T> response(T value) {return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
    @GetMapping("/admin/kiosks/config") @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('SYSTEM_CONFIGURE')")
    public ResponseEntity<KioskConfig> config(Authentication auth) {return response(new KioskConfig(service.configured(UUID.fromString(auth.getName()))));}
    @GetMapping("/admin/kiosks") @PreAuthorize("hasAuthority('SYSTEM_CONFIGURE')")
    public ResponseEntity<?> devices(Authentication auth) {return response(service.devices(UUID.fromString(auth.getName())));}
    @PostMapping("/admin/kiosks") @PreAuthorize("hasAuthority('SYSTEM_CONFIGURE')")
    public ResponseEntity<?> provision(Authentication auth,@Valid @RequestBody Label request) {return response(service.provision(UUID.fromString(auth.getName()),request.label()));}
    @PostMapping("/admin/kiosks/{id}/revoke") @PreAuthorize("hasAuthority('SYSTEM_CONFIGURE')")
    public ResponseEntity<?> revoke(Authentication auth,@PathVariable UUID id,@Valid @RequestBody Version request) {service.revoke(UUID.fromString(auth.getName()),id,request.version());return response(java.util.Map.of("revoked",true));}
    @GetMapping("/reception/kiosk-intakes") @PreAuthorize("hasAuthority('QR_PASS_VERIFY')")
    public ResponseEntity<?> pending(Authentication auth) {return response(service.pending(UUID.fromString(auth.getName())));}
    @PostMapping("/reception/kiosk-intakes/{id}/resolve") @PreAuthorize("hasAuthority('QR_PASS_VERIFY')")
    public ResponseEntity<?> resolve(Authentication auth,@PathVariable UUID id,@Valid @RequestBody Version request) {service.resolve(UUID.fromString(auth.getName()),id,request.version());return response(java.util.Map.of("resolved",true));}
    @Schema(name = "KioskConfig")
    public record KioskConfig(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {}
    public record Label(@NotBlank @Size(max=80) String label) {}
    public record Version(@NotNull @Min(0) Long version) {}
}
