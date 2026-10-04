package com.brainserve.appointment.appointment.api;

import com.brainserve.appointment.shared.api.SearchContract;
import java.time.Instant;
import java.util.UUID;

/** Search boundary: only current authorized appointment projections leave this module. */
public interface AppointmentSearch {
    SearchContract.Group search(UUID actor, String query, int page, int size);
    SearchContract.OpenRecord open(UUID actor, UUID id);
    Record visible(UUID actor, UUID id);
    record Record(UUID id, String referenceNumber, String visitorName, String status, String type, String purpose,
                  Instant slotStart, Instant slotEnd, UUID routingDepartmentId, UUID hostEmployeeId) {}
}
