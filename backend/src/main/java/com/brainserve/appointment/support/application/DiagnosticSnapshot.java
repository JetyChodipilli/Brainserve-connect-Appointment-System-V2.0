package com.brainserve.appointment.support.application;

import java.time.Instant;
import java.util.UUID;

/** The complete export allowlist. No business identity, free text, or source correlation is a field. */
public record DiagnosticSnapshot(
        int schemaVersion,
        UUID supportReference,
        Instant generatedAt,
        Instant windowStart,
        Instant windowEnd,
        Environment environment,
        String releaseVersion,
        String buildRevision,
        DatabaseStatus databaseStatus,
        int migrationVersion,
        ConnectionCounts connections,
        DeliveryCounts deliveries) {
    public enum Environment { UNKNOWN, DEVELOPMENT, TEST, STAGING, PRODUCTION }
    public enum DatabaseStatus { AVAILABLE }
    public record ConnectionCounts(int active, int needsReconnect, int revoked) {}
    public record DeliveryCounts(int pending, int running, int delivered, int failed,
                                 int needsReconnect, int cancelled, int superseded) {}
}
