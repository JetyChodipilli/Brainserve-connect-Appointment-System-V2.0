package com.brainserve.appointment.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id @UuidGenerator
    private UUID id;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "actor_id", nullable = false, updatable = false, length = 120)
    private String actorId;
    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;
    @Column(name = "target_type", nullable = false, updatable = false, length = 80)
    private String targetType;
    @Column(name = "target_id", nullable = false, updatable = false, length = 120)
    private String targetId;
    @Column(nullable = false, updatable = false, length = 20)
    private String outcome;
    @Column(name = "correlation_id", updatable = false, length = 100)
    private String correlationId;
    @Column(name = "details_json", updatable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String detailsJson;

    protected AuditEvent() {}
    public AuditEvent(String actorId, String eventType, String targetType, String targetId, String outcome,
                      String correlationId, String detailsJson) {
        this.occurredAt = Instant.now(); this.actorId = actorId; this.eventType = eventType;
        this.targetType = targetType; this.targetId = targetId; this.outcome = outcome;
        this.correlationId = correlationId; this.detailsJson = detailsJson;
    }
    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActorId() { return actorId; }
    public String getEventType() { return eventType; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getOutcome() { return outcome; }
    public String getCorrelationId() { return correlationId; }
    public String getDetailsJson() { return detailsJson; }
}
