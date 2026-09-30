package com.brainserve.appointment.appointment.api;

import java.util.UUID;

public interface AppointmentAccess {
    AccessAppointment requireForCheckIn(UUID appointmentId);
    AccessAppointment requireForCheckInByReference(String referenceNumber);
    void markCheckedIn(UUID appointmentId);
    void markCheckedOut(UUID appointmentId);
    record AccessAppointment(UUID id, String visitorName, boolean approved) {}
}
