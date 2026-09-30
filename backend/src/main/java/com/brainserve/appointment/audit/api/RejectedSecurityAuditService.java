package com.brainserve.appointment.audit.api;

import com.brainserve.appointment.audit.domain.AuditEvent;
import com.brainserve.appointment.audit.infrastructure.AuditEventRepository;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RejectedSecurityAuditService {
    private final AuditEventRepository events;

    public RejectedSecurityAuditService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String targetType, String targetId, String detailsJson) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null && authentication.isAuthenticated()
                ? authentication.getName() : "public";
        events.save(new AuditEvent(actor, eventType, targetType, targetId, "REJECTED",
                MDC.get("correlationId"), detailsJson));
    }
}
