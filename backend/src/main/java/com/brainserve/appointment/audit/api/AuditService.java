package com.brainserve.appointment.audit.api;

import com.brainserve.appointment.audit.domain.AuditEvent;
import com.brainserve.appointment.audit.infrastructure.AuditEventRepository;
import com.brainserve.appointment.realtime.api.WorkspaceChangeEvent;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditEventRepository events;
    private final ApplicationEventPublisher eventPublisher;

    public AuditService(AuditEventRepository events, ApplicationEventPublisher eventPublisher) {
        this.events = events;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void record(String eventType, String targetType, String targetId, String detailsJson) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null && authentication.isAuthenticated() ? authentication.getName() : "public";
        events.save(new AuditEvent(actor, eventType, targetType, targetId, "SUCCESS", MDC.get("correlationId"), detailsJson));
        eventPublisher.publishEvent(new WorkspaceChangeEvent(eventType, targetType, targetId));
    }

}
