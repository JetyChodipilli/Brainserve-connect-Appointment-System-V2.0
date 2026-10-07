package com.brainserve.appointment.integration.application;

import com.brainserve.appointment.appointment.api.AppointmentEvents;
import com.brainserve.appointment.reception.api.ReceptionEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Synchronous listeners intentionally join the writer; rollback removes both event and outbox. */
@Component
public class IntegrationEventCapture {
    private final IntegrationService service;
    public IntegrationEventCapture(IntegrationService service) { this.service = service; }
    @EventListener
    public void appointment(AppointmentEvents.IntegrationChange event) {
        service.capture(event.eventId(), event.appointmentId(), event.eventType(), event.occurredAt(), Map.of(
                "status",event.status(),"appointmentType",event.appointmentType(),
                "slotStart",event.slotStart().toString(),"slotEnd",event.slotEnd().toString()));
    }
    @EventListener
    public void arrival(ReceptionEvents.VisitorArrived event) {
        service.capture(event.eventId(), event.appointmentId(),"VISITOR_ARRIVED",event.occurredAt(),Map.of("arrived",true));
    }
}
