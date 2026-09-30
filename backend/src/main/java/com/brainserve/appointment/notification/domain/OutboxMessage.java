package com.brainserve.appointment.notification.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "notification_outbox")
public class OutboxMessage extends AuditableEntity {
    public enum Status { PENDING, PROCESSING, SENT, DEAD }

    @Column(name = "event_key", nullable = false, unique = true, length = 160)
    private String eventKey;
    @Column(nullable = false, length = 20)
    private String channel;
    @Column(nullable = false, length = 180)
    private String destination;
    @Column(nullable = false, length = 100)
    private String template;
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payloadJson;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    protected OutboxMessage() {}
    public OutboxMessage(String eventKey, String destination, String template, String payloadJson) {
        this.eventKey = eventKey; this.channel = "EMAIL"; this.destination = destination;
        this.template = template; this.payloadJson = payloadJson;
    }
    public String getDestination() { return destination; }
    public String getTemplate() { return template; }
    public String getPayloadJson() { return payloadJson; }
    public int getAttemptCount() { return attemptCount; }
    public void markProcessing() {
        status = Status.PROCESSING;
        attemptCount++;
        // A crashed dispatcher must not strand this row in PROCESSING forever.
        // The ready query may reclaim it after this short delivery lease expires.
        nextAttemptAt = Instant.now().plusSeconds(300);
    }
    public void markSent() { status = Status.SENT; sentAt = Instant.now(); lastErrorCode = null; }
    public void retry(String errorCode) {
        lastErrorCode = errorCode;
        if (attemptCount >= 5) { status = Status.DEAD; return; }
        status = Status.PENDING;
        nextAttemptAt = Instant.now().plusSeconds(Math.min(3600, 30L * (1L << Math.min(attemptCount, 6))));
    }
}
