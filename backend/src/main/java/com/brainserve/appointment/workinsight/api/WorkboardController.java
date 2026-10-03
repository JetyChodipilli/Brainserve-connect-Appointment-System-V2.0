package com.brainserve.appointment.workinsight.api;

import com.brainserve.appointment.workinsight.application.WorkboardQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.brainserve.appointment.workinsight.application.WorkboardQueryService.*;

@RestController
@RequestMapping("/api/v1/workboard")
@PreAuthorize("hasAuthority('WORK_TASK_READ')")
public class WorkboardController {
    private final WorkboardQueryService service;
    public WorkboardController(WorkboardQueryService service) { this.service = service; }
    @GetMapping
    Page list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="TODAY") Period scope,
              @RequestParam(defaultValue="ALL") QuickFilter quickFilter, @RequestParam(defaultValue="") String query,
              @RequestParam(defaultValue="ALL") String status, @RequestParam(defaultValue="") String branch,
              @RequestParam(defaultValue="DUE_DATE") Sort sort, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return service.list(actor(jwt), new Criteria(scope, quickFilter, query, status, branch, sort), page, size);
    }
    @GetMapping("/{taskId}")
    Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) { return service.detail(actor(jwt), taskId); }
    @GetMapping("/preferences")
    Preferences preferences(@AuthenticationPrincipal Jwt jwt) { return service.preferences(actor(jwt)); }
    @PutMapping("/preferences")
    Preferences preferences(@AuthenticationPrincipal Jwt jwt, @RequestBody PreferencesUpdate update) { return service.savePreferences(actor(jwt), update); }
    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
