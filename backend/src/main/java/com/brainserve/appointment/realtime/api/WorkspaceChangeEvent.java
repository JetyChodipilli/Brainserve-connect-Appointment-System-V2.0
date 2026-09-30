package com.brainserve.appointment.realtime.api;

/**
 * A data-minimal signal asking connected clients to refresh their own
 * role-scoped workspace after an audited transaction commits.
 */
public record WorkspaceChangeEvent(
        String eventType,
        String targetType,
        String targetId
) {
}
