package com.brainserve.appointment.configuration.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public final class ReleaseProfileModels {
    private ReleaseProfileModels() {}
    public enum Status { UNCONFIGURED, PILOT, ACTIVE, PAUSED, CANCELLED }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    @Schema(name = "ReleaseAgreementProfile")
    public record Profile(
            @NotNull Status status,
            @NotNull @Size(max = 80) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*") String reference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDate startsOn,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDate renewsOn,
            @NotNull @Size(max = 120) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*") String supportOwner,
            @NotNull @Email @Size(max = 254) String supportEmail,
            @NotNull @Size(max = 160) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*") String supportHours) {}
    @Schema(name = "ReleaseProfileWrite")
    public record Write(@NotNull @Min(0) Long expectedVersion, @NotNull @Valid Profile profile) {}
    @Schema(name = "ReleaseProfileView")
    public record View(long version, Profile profile, String officeZone, LocalDate officeDate, boolean renewalDue) {}
}
