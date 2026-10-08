package com.brainserve.appointment.reception.api;
import com.brainserve.appointment.reception.application.VisitorBadgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/reception/badges")
public class VisitorBadgeController {
    private final VisitorBadgeService service;
    public VisitorBadgeController(VisitorBadgeService service) {this.service=service;}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('QR_PASS_VERIFY') and hasAuthority('VISITOR_CHECK_IN')")
    public ResponseEntity<?> badge(Authentication auth,@PathVariable UUID id) {return ResponseEntity.ok().header("Cache-Control","no-store").body(service.prepare(UUID.fromString(auth.getName()),id));}
}
