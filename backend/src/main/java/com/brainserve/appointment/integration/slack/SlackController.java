package com.brainserve.appointment.integration.slack;

import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.integration.application.IntegrationService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/slack")
@PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
public class SlackController {
    private final IntegrationService service;
    public SlackController(IntegrationService service) { this.service=service; }
    @GetMapping("/config")
    public ResponseEntity<SlackModels.Config> config(@AuthenticationPrincipal Jwt jwt) { return noStore(service.slackConfig(UUID.fromString(jwt.getSubject()))); }
    @PostMapping("/connections")
    public ResponseEntity<IntegrationModels.Connection> create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody SlackModels.Create command) { return noStore(service.createSlack(UUID.fromString(jwt.getSubject()),command)); }
    @GetMapping("/connections/{id}")
    public ResponseEntity<SlackAdapter.Metadata> metadata(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return noStore(service.slackMetadata(UUID.fromString(jwt.getSubject()),id)); }
    @PostMapping("/connections/{id}/renew")
    public ResponseEntity<IntegrationModels.Connection> renew(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody IntegrationModels.Reconnect command) { return noStore(service.renewSlack(UUID.fromString(jwt.getSubject()),id,command)); }
    @PostMapping("/connections/{id}/revocation/retry")
    public ResponseEntity<SlackAdapter.Metadata> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody IntegrationModels.Version command) { return noStore(service.retrySlackRevocation(UUID.fromString(jwt.getSubject()),id,command.expectedVersion())); }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
