package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.reporting.application.RoleDashboardQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final RoleDashboardQueryService dashboards;
    public DashboardController(RoleDashboardQueryService dashboards) { this.dashboards = dashboards; }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW','APPOINTMENT_APPROVE','VISITOR_CHECK_IN')")
    RoleDashboardQueryService.DashboardSummary summary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) RoleDashboardQueryService.PeriodPreset period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.summary(UUID.fromString(jwt.getSubject()), period, from, to);
    }
}
