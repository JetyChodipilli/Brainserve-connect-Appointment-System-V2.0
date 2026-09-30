package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.reporting.application.ReportExportService;
import com.brainserve.appointment.reporting.application.RoleDataScopeService;
import com.brainserve.appointment.reporting.domain.ReportExportJob;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/report-exports")
@PreAuthorize("isAuthenticated()")
public class ReportExportController {
    private final ReportExportService exports;
    private final RoleDataScopeService scopes;
    private final ZoneId officeZone;

    public ReportExportController(ReportExportService exports, RoleDataScopeService scopes,
                                  @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.exports = exports; this.scopes = scopes; this.officeZone = ZoneId.of(officeZone);
    }

    @PostMapping
    ReportExportService.View request(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ExportInput input) {
        UUID actor = UUID.fromString(jwt.getSubject());
        String role = scopes.resolve(actor).role();
        boolean organizationWide = role.equals("ROLE_SYSTEM_ADMIN") || role.equals("ROLE_CEO");
        HistoryFilter filter = HistoryFilter.normalize(input.from(), input.to(), input.departmentId(),
                input.status(), input.query(), null, 100, officeZone, organizationWide);
        return exports.request(actor, input.dataset(), input.format(), filter);
    }

    @GetMapping
    List<ReportExportService.View> list(@AuthenticationPrincipal Jwt jwt) {
        return exports.list(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{id}/download-url")
    ReportExportService.DownloadView download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return exports.download(UUID.fromString(jwt.getSubject()), id);
    }

    @PostMapping("/{id}/retry")
    ReportExportService.View retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return exports.retry(UUID.fromString(jwt.getSubject()), id);
    }

    public record ExportInput(@NotNull HistoryDataset dataset, @NotNull ReportExportJob.ExportFormat format,
                              @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                              @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                              UUID departmentId, String status, String query) {}
}
