package com.brainserve.appointment.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal read model. Callers must authorize the individual business record before and after reading. */
public interface ActivityHistory {
    Page task(UUID taskId,int page,int size);
    Page appointment(UUID appointmentId,int page,int size);
    record Actor(String id,String name,String role,boolean snapshotRecorded) {}
    record Event(String id,Instant occurredAt,String eventType,String title,Actor actor,Integer cycle,Long evidenceVersion,
                 UUID departmentId,String correlationId,String deliveryStatus,String note) {}
    record Page(List<Event> events,int page,int size,boolean hasMore) {}
}
