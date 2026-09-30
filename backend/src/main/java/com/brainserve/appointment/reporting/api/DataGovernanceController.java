package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.reporting.application.DataGovernanceService;
import com.brainserve.appointment.reporting.application.GovernanceLedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/data-governance")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class DataGovernanceController {
    private final DataGovernanceService governance;
    private final GovernanceLedgerService ledger;

    public DataGovernanceController(DataGovernanceService governance, GovernanceLedgerService ledger) {
        this.governance = governance;
        this.ledger = ledger;
    }

    @GetMapping("/retention-policies")
    List<DataGovernanceService.RetentionPolicy> policies() { return governance.policies(); }

    @PutMapping("/retention-policies/{dataset}")
    DataGovernanceService.RetentionPolicy update(@PathVariable String dataset, @Valid @RequestBody PolicyInput input,
                                                 Authentication authentication) {
        return governance.update(dataset, input.hotDays(), input.warmMonths(), input.archiveYears(),
                input.disposalAction(), input.enabled(), authentication.getName());
    }

    @GetMapping("/archive-manifests")
    List<DataGovernanceService.ArchiveManifest> manifests() { return governance.manifests(); }

    @GetMapping("/overview")
    DataGovernanceService.GovernanceOverview overview() { return governance.overview(); }

    @GetMapping("/legal-holds")
    List<DataGovernanceService.LegalHold> legalHolds() { return governance.legalHolds(); }

    @PostMapping("/legal-holds")
    DataGovernanceService.LegalHold placeHold(@Valid @RequestBody LegalHoldInput input,
                                              Authentication authentication) {
        return governance.placeHold(input.dataset(), input.holdKind(), input.scopeType(),
                input.scopeRef(), input.caseReference(), input.reason(), input.reviewOn(),
                authentication.getName());
    }

    @PostMapping("/legal-holds/{holdId}/release")
    DataGovernanceService.LegalHold releaseHold(@PathVariable UUID holdId,
                                                @Valid @RequestBody ReleaseHoldInput input,
                                                Authentication authentication) {
        return governance.releaseHold(holdId, input.reason(), authentication.getName());
    }

    @GetMapping("/ledger")
    GovernanceLedgerResponse ledger(@RequestParam(defaultValue = "50") @Min(25) @Max(100) int size) {
        var page = ledger.recent(size);
        return new GovernanceLedgerResponse(page.items(), page.integrityValid(), page.entriesChecked());
    }

    public record PolicyInput(@Min(1) @Max(3650) int hotDays, @Min(1) @Max(240) int warmMonths,
                              @Min(1) @Max(25) int archiveYears,
                              @NotBlank String disposalAction, boolean enabled) {}
    public record LegalHoldInput(@NotBlank String dataset, @NotBlank String holdKind,
                                 @NotBlank String scopeType, String scopeRef,
                                 @NotBlank @Size(max = 120) String caseReference,
                                 @NotBlank @Size(max = 1200) String reason, LocalDate reviewOn) {}
    public record ReleaseHoldInput(@NotBlank @Size(max = 1200) String reason) {}
    public record GovernanceLedgerResponse(List<GovernanceLedgerService.LedgerEntry> items,
            boolean integrityValid, long entriesChecked) {}
}
