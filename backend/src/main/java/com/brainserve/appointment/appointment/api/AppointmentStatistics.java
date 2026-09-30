package com.brainserve.appointment.appointment.api;

public interface AppointmentStatistics {
    long awaitingApproval();
    long activeVisits();
}
