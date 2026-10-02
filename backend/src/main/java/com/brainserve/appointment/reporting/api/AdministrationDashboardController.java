package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.reporting.application.AdministrationDashboardService;
import com.brainserve.appointment.reporting.application.RoleDashboardQueryService.PeriodPreset;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard/cards")
public class AdministrationDashboardController {
    private final AdministrationDashboardService dashboards;
    public AdministrationDashboardController(AdministrationDashboardService dashboards) { this.dashboards = dashboards; }

    // Authority is checked from current persisted state, including per-card denies.
    @GetMapping
    public AdministrationDashboardService.DashboardCards cards(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) PeriodPreset period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.cards(UUID.fromString(jwt.getSubject()), period, from, to);
    }

    @GetMapping("/{metricId}/records")
    public AdministrationDashboardService.MetricRecords records(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String metricId, @RequestParam(required = false) PeriodPreset period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return dashboards.records(UUID.fromString(jwt.getSubject()), metricId, period, from, to, page, size);
    }
}
