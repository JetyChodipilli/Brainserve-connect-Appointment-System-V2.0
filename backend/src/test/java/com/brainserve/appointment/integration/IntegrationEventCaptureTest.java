package com.brainserve.appointment.integration;

import com.brainserve.appointment.appointment.api.AppointmentEvents;
import com.brainserve.appointment.integration.application.IntegrationEventCapture;
import com.brainserve.appointment.integration.application.IntegrationService;
import com.brainserve.appointment.reception.api.ReceptionEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IntegrationEventCaptureTest {
    @Test void calendarSnapshotsCarryOnlyFiniteStateAndScheduleFieldsWithOriginalEventId() {
        var service = mock(IntegrationService.class);
        var listener = new IntegrationEventCapture(service);
        UUID event = UUID.randomUUID(), resource = UUID.randomUUID();
        Instant start = Instant.parse("2026-10-08T09:00:00Z"), end = start.plusSeconds(1800), now = start.minusSeconds(60);
        listener.appointment(new AppointmentEvents.IntegrationChange(event,resource,"APPOINTMENT_UPDATED","APPROVED","HR_VISIT",start,end,now));
        verify(service).capture(event,resource,"APPOINTMENT_UPDATED",now,Map.of("status","APPROVED","appointmentType","HR_VISIT","slotStart",start.toString(),"slotEnd",end.toString()));
    }
    @Test void arrivalSnapshotsCarryNoVisitorIdentityOrBadge() {
        var service = mock(IntegrationService.class);
        UUID event = UUID.randomUUID(), resource = UUID.randomUUID(); Instant now = Instant.now();
        new IntegrationEventCapture(service).arrival(new ReceptionEvents.VisitorArrived(event,resource,now));
        verify(service).capture(event,resource,"VISITOR_ARRIVED",now,Map.of("arrived",true));
    }
}
