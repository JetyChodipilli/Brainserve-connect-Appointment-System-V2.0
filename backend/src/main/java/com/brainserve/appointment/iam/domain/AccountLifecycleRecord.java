package com.brainserve.appointment.iam.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_lifecycle_record")
public class AccountLifecycleRecord extends AuditableEntity {
    @Column(name = "closure_request_id", nullable = false, updatable = false)
    private UUID closureRequestId;
    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;
    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;
    @Column(name = "from_status", updatable = false, length = 40)
    private String fromStatus;
    @Column(name = "to_status", nullable = false, updatable = false, length = 40)
    private String toStatus;
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;
    @Column(nullable = false, updatable = false, length = 1200)
    private String detail;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AccountLifecycleRecord() {}

    public AccountLifecycleRecord(UUID closureRequestId, UUID targetUserId, String eventType,
                                  String fromStatus, String toStatus, UUID actorUserId, String detail) {
        this.closureRequestId = closureRequestId; this.targetUserId = targetUserId;
        this.eventType = eventType; this.fromStatus = fromStatus; this.toStatus = toStatus;
        this.actorUserId = actorUserId; this.detail = detail; this.occurredAt = Instant.now();
    }

    public UUID getClosureRequestId() { return closureRequestId; }
    public UUID getTargetUserId() { return targetUserId; }
    public String getEventType() { return eventType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public UUID getActorUserId() { return actorUserId; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
