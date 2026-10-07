package com.brainserve.appointment.notification.api;

import com.brainserve.appointment.notification.application.NotificationPreferenceService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notification-preferences")
@PreAuthorize("isAuthenticated()")
public class NotificationPreferenceController {
    private final NotificationPreferenceService service;
    public NotificationPreferenceController(NotificationPreferenceService service) { this.service=service; }
    @GetMapping
    ResponseEntity<NotificationPreferenceService.Preference> read(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(UUID.fromString(jwt.getSubject())));
    }
    @PutMapping
    ResponseEntity<NotificationPreferenceService.Preference> save(@AuthenticationPrincipal Jwt jwt,
            @RequestBody NotificationPreferenceService.Preference value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(UUID.fromString(jwt.getSubject()),value));
    }
}
