package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.reporting.application.VisitTypeReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class VisitTypeReportController {
    private final VisitTypeReportService reports;
    public VisitTypeReportController(VisitTypeReportService reports) { this.reports = reports; }

    @GetMapping("/visit-types")
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW','APPOINTMENT_APPROVE','VISITOR_CHECK_IN')")
    public List<VisitTypeReportService.VisitTypeCount> visitTypes(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.counts(UUID.fromString(jwt.getSubject()), from, to);
    }
}
