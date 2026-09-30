package com.brainserve.appointment.operations.api;

import com.brainserve.appointment.operations.application.IntegrationHealthService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/integrations")
public class IntegrationHealthController {
    private final IntegrationHealthService health;

    public IntegrationHealthController(IntegrationHealthService health) {
        this.health = health;
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    IntegrationHealthService.IntegrationOverview inspect() {
        return health.inspect();
    }
}
