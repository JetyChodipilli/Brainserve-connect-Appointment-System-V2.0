package com.brainserve.appointment.integration.slack;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class SlackModels {
    private SlackModels() {}
    public record Config(boolean configured, String scope, boolean usesDedicatedBot) {}
    public record Create(@NotNull UUID requestId,@NotBlank @Size(max=80) String label,@NotBlank @Pattern(regexp="[CG][A-Z0-9]{8,31}") String channelId,
                         @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min=16,max=4096) String credential,@NotNull Instant credentialExpiresAt) {
        @Override public String toString() { return "SlackCreate[credential=REDACTED]"; }
    }
}
