package com.brainserve.appointment.essentiallog.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "essential_log_record")
public class EssentialLogRecord extends AuditableEntity {
    @Column(nullable = false, updatable = false, length = 60)
    private String category;
    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;
    @Column(name = "subject_type", nullable = false, updatable = false, length = 60)
    private String subjectType;
    @Column(name = "subject_id", nullable = false, updatable = false, length = 120)
    private String subjectId;
    @Column(name = "reference_id", updatable = false, length = 120)
    private String referenceId;
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;
    @Column(name = "approver_user_id", updatable = false)
    private UUID approverUserId;
    @Column(nullable = false, updatable = false, length = 30)
    private String status;
    @Column(nullable = false, updatable = false, length = 180)
    private String title;
    @Column(nullable = false, updatable = false, length = 1200)
    private String detail;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected EssentialLogRecord() {}

    public EssentialLogRecord(String category, String eventType, String subjectType, String subjectId,
                              String referenceId, UUID actorUserId, UUID approverUserId, String status,
                              String title, String detail) {
        this.category = normalize(category); this.eventType = normalize(eventType);
        this.subjectType = normalize(subjectType); this.subjectId = normalize(subjectId);
        this.referenceId = nullable(referenceId); this.actorUserId = actorUserId;
        this.approverUserId = approverUserId; this.status = normalize(status);
        this.title = normalize(title); this.detail = normalize(detail); this.occurredAt = Instant.now();
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : normalize(value); }

    public String getCategory() { return category; }
    public String getEventType() { return eventType; }
    public String getSubjectType() { return subjectType; }
    public String getSubjectId() { return subjectId; }
    public String getReferenceId() { return referenceId; }
    public UUID getActorUserId() { return actorUserId; }
    public UUID getApproverUserId() { return approverUserId; }
    public String getStatus() { return status; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
