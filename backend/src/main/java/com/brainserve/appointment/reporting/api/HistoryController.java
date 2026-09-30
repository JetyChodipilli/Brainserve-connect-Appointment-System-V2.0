package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.reporting.application.RoleAwareHistoryQueryService;
import com.brainserve.appointment.reporting.application.RoleDataScopeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {
    private final RoleAwareHistoryQueryService history;
    private final RoleDataScopeService scopes;
    private final ZoneId officeZone;

    public HistoryController(RoleAwareHistoryQueryService history, RoleDataScopeService scopes,
                             @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.history = history;
        this.scopes = scopes;
        this.officeZone = ZoneId.of(officeZone);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    CursorPage<HistoryRow> search(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam HistoryDataset dataset,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                  @RequestParam(required = false) UUID departmentId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String query,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer size) {
        UUID actor = UUID.fromString(jwt.getSubject());
        String role = scopes.resolve(actor).role();
        boolean organizationWide = role.equals("ROLE_SYSTEM_ADMIN") || role.equals("ROLE_CEO");
        HistoryFilter filter = HistoryFilter.normalize(from, to, departmentId, status, query, cursor, size,
                officeZone, organizationWide);
        return history.search(actor, dataset, filter);
    }
}
