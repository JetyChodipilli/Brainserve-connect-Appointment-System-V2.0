package com.brainserve.appointment.integration.application;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class IntegrationDispatcher {
    private static final Logger LOG=LoggerFactory.getLogger(IntegrationDispatcher.class);
    private final IntegrationService service;
    private final boolean enabled;
    public IntegrationDispatcher(IntegrationService service, @Value("${brainserve.integrations.enabled:true}") boolean enabled) {
        this.service = service; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString="${brainserve.integrations.poll-ms:10000}",initialDelayString="${brainserve.integrations.poll-ms:10000}")
    public void dispatch() {
        if (!enabled) return;
        for (var id : service.dueReconciliations()) {
            try { service.reconcileBatch(id); }
            catch (RuntimeException exception) { LOG.warn("Calendar reconciliation deferred; the durable batch will recover"); }
        }
        for (var id : service.due()) {
            try { service.claim(id,Instant.now()).ifPresent(claim -> service.complete(claim,Instant.now())); }
            catch (RuntimeException exception) { LOG.warn("Integration delivery deferred; recovery will use the retained lease"); }
        }
    }
}
