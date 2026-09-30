package com.brainserve.appointment.appointment.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Component
public class PastAppointmentCancellationJob {
    private final AppointmentService appointments;

    public PastAppointmentCancellationJob(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cancelPastVisitsAfterStartup() {
        appointments.cancelPastUnfinishedVisits();
    }

    @Scheduled(cron = "${brainserve.appointment.past-cancellation-cron:0 5 0 * * *}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    public void cancelPastUnfinishedVisits() {
        appointments.cancelPastUnfinishedVisits();
    }
}
