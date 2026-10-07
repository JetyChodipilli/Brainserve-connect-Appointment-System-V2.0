package com.brainserve.appointment.reception.api;

import java.time.Instant;
import java.util.UUID;

public final class ReceptionEvents {
    private ReceptionEvents() {}
    /** No visitor identity, badge or free-form content crosses the integration boundary. */
    public record VisitorArrived(UUID eventId, UUID appointmentId, Instant occurredAt) {}
}
