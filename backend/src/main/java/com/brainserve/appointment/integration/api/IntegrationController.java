package com.brainserve.appointment.integration.api;

import com.brainserve.appointment.integration.application.IntegrationService;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/v1/integrations")
@PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
public class IntegrationController {
    private final IntegrationService integrations;
    public IntegrationController(IntegrationService integrations) { this.integrations = integrations; }
    @GetMapping("/connections")
    public ResponseEntity<List<IntegrationModels.Connection>> connections(@AuthenticationPrincipal Jwt jwt) {
        return noStore(integrations.connections(actor(jwt)));
    }
    @PostMapping("/connections")
    public ResponseEntity<IntegrationModels.Connection> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IntegrationModels.Create command) {
        return noStore(integrations.create(actor(jwt), command));
    }
    @PostMapping("/connections/{id}/reconnect")
    public ResponseEntity<IntegrationModels.Connection> reconnect(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody IntegrationModels.Reconnect command) {
        return noStore(integrations.reconnect(actor(jwt), id, command));
    }
    @PostMapping("/connections/{id}/revoke")
    public ResponseEntity<IntegrationModels.Connection> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody IntegrationModels.Version command) {
        if (command == null) throw new BusinessException("INVALID_INTEGRATION_REQUEST","Supply the observed connection version",HttpStatus.BAD_REQUEST);
        return noStore(integrations.revoke(actor(jwt), id, command.expectedVersion()));
    }
    @PostMapping("/connections/{id}/test")
    public ResponseEntity<IntegrationModels.Delivery> test(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody IntegrationModels.Test command) {
        return noStore(integrations.test(actor(jwt), id, command));
    }
    @GetMapping("/connections/{id}/deliveries")
    public ResponseEntity<Page<IntegrationModels.Delivery>> deliveries(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(defaultValue="0") int page) {
        return noStore(integrations.deliveries(actor(jwt), id, page));
    }
    @PostMapping("/deliveries/{id}/retry")
    public ResponseEntity<IntegrationModels.Delivery> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody IntegrationModels.Retry command) {
        return noStore(integrations.retry(actor(jwt), id, command));
    }
    @GetMapping("/deliveries/{id}/attempts")
    public ResponseEntity<List<IntegrationModels.Attempt>> attempts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return noStore(integrations.attempts(actor(jwt), id));
    }
    private UUID actor(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException("ACCOUNT_INACTIVE", "An active System Admin is required", HttpStatus.FORBIDDEN);
        }
    }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
