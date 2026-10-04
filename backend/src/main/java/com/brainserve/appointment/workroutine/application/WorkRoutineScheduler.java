package com.brainserve.appointment.workroutine.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class WorkRoutineScheduler {
    private final WorkRoutineService service;
    private final boolean enabled;
    public WorkRoutineScheduler(WorkRoutineService service,@Value("${brainserve.work-routines.enabled:true}") boolean enabled) { this.service=service; this.enabled=enabled; }
    @Scheduled(fixedDelayString="${brainserve.work-routines.poll-ms:15000}")
    public void poll() { if(enabled) service.runDue(Instant.now(),50); }
}
