package com.brainserve.appointment.worktask.api;

import com.brainserve.appointment.worktask.application.WorkTaskHandoverService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-tasks/{id}/handover")
public class WorkTaskHandoverController {
    private final WorkTaskHandoverService service;
    public WorkTaskHandoverController(WorkTaskHandoverService service) {this.service=service;}
    @GetMapping
    ResponseEntity<WorkTaskHandoverService.View> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.get(UUID.fromString(jwt.getSubject()),id));
    }
    @PostMapping
    ResponseEntity<WorkTaskHandoverService.View> handover(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody WorkTaskHandoverService.Change request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.handover(UUID.fromString(jwt.getSubject()),id,request));
    }
    /** Synchronous invalidation; the consumer retains the old audit before removing current authority. */
    public record HandoverInvalidated(UUID handoverId,UUID taskId) {}
}
