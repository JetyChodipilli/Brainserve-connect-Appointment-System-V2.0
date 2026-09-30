package com.brainserve.appointment.reception.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReceptionRecords {
    List<AccessRecord> forAppointments(Collection<UUID> appointmentIds);

    record AccessRecord(UUID appointmentId, String badgeNumber, Instant checkedInAt,
                        Instant checkedOutAt, String processedBy) {}
}
