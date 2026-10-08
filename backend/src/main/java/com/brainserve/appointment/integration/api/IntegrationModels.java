package com.brainserve.appointment.integration.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Provider choices and public projections contain no credential or business payload. */
public final class IntegrationModels {
    private IntegrationModels() {}
    public enum Provider {
        SIMULATOR_CALENDAR("CALENDAR", List.of("calendar.events.write")),
        GOOGLE_CALENDAR("CALENDAR", List.of("https://www.googleapis.com/auth/calendar.app.created")),
        SIMULATOR_MESSAGING("MESSAGING", List.of("messages.send"));
        private final String kind;
        private final List<String> scopes;
        Provider(String kind, List<String> scopes) { this.kind = kind; this.scopes = scopes; }
        public String kind() { return kind; }
        public List<String> scopes() { return scopes; }
    }
    public enum Scenario { SUCCESS, OUTAGE, RATE_LIMITED, REAUTH_REQUIRED, PERMANENT_FAILURE }
    public record Connection(UUID id, Provider provider, String kind, String label, UUID ownerId,
                             List<String> minimumScopes, String status, long credentialVersion,
                             Instant credentialExpiresAt, long version, Instant lastCheckedAt,
                             String lastResultCode, Instant createdAt, Instant updatedAt) {}
    public record Delivery(UUID id, UUID connectionId, UUID businessEventId, String eventType,
                           UUID resourceId, long businessRevision, String status, int attempts,
                           int totalAttempts, int manualRetries, Instant nextAttemptAt,
                           String lastResultCode, long version, Instant createdAt, Instant deliveredAt) {}
    public record Attempt(UUID id, UUID deliveryId, int attemptNumber, long credentialVersion,
                          String outcome, Instant startedAt, Instant completedAt) {}
    public record Create(@NotNull UUID requestId, @NotNull Provider provider, @NotBlank @Size(max=80) String label,
                         @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min=16,max=4096) String credential,
                         @NotNull Instant credentialExpiresAt) {
        @Override public String toString() { return "Create[credential=REDACTED]"; }
    }
    public record Reconnect(@NotNull @PositiveOrZero Long expectedVersion,
                            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min=16,max=4096) String credential,
                            @NotNull Instant credentialExpiresAt) {
        @Override public String toString() { return "Reconnect[credential=REDACTED]"; }
    }
    public record Version(@NotNull @PositiveOrZero Long expectedVersion) {}
    public record Test(@NotNull UUID requestId, @NotNull @PositiveOrZero Long expectedVersion, @NotNull Scenario scenario) {}
    public record Reconcile(@NotNull UUID requestId, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Reconciliation(UUID id, String status, int processed, Instant createdAt, Instant completedAt) {}
    public record Retry(@NotNull UUID requestId, @NotNull @PositiveOrZero Long expectedVersion) {}
}
