package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.HrAccountLifecycleService;
import com.brainserve.appointment.iam.domain.UserAccount;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/governance/hr-accounts")
@PreAuthorize("hasAuthority('HR_ACCOUNT_DEACTIVATE')")
public class HrAccountLifecycleController {
    private final HrAccountLifecycleService service;
    public HrAccountLifecycleController(HrAccountLifecycleService service) { this.service = service; }
    @GetMapping List<Response> list() { return service.list().stream().map(Response::from).toList(); }
    public record Response(UUID userId, String fullName, String email, String status, boolean enabled) {
        static Response from(UserAccount value) { return new Response(value.getId(), value.getFullName(), value.getEmail(), value.getStatus().name(), value.isEnabled()); }
    }
}
