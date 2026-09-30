package com.brainserve.appointment.appointment.api;

import java.time.Instant;
import java.util.UUID;

public interface AppointmentAvailability {
    boolean isSlotReserved(UUID hostEmployeeId, Instant slotStart, Instant slotEnd);
}
