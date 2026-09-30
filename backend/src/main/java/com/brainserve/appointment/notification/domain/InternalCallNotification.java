package com.brainserve.appointment.notification.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "internal_call_notification")
public class InternalCallNotification extends AuditableEntity {
    public enum DeliveryStatus { QUEUED, DELIVERED, FAILED }
    public enum MessagePriority { NORMAL, HIGH, URGENT }
    public enum MessageCategory { GENERAL, ACTION_REQUIRED, VISITOR, WORK, INSIGHT, LEAVE }

    @Column(name = "sender_user_id", nullable = false)
    private UUID senderUserId;
    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;
    @Column(name = "sender_name", nullable = false, length = 170)
    private String senderName;
    @Column(name = "recipient_name", nullable = false, length = 170)
    private String recipientName;
    @Column(nullable = false, length = 500)
    private String message;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessagePriority priority = MessagePriority.NORMAL;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MessageCategory category = MessageCategory.GENERAL;
    @Column(name = "conversation_key", nullable = false, length = 73)
    private String conversationKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    private DeliveryStatus deliveryStatus = DeliveryStatus.QUEUED;
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;
    @Column(name = "next_delivery_attempt_at")
    private Instant nextDeliveryAttemptAt;
    @Column(name = "kafka_published_at")
    private Instant kafkaPublishedAt;
    @Column(name = "last_delivery_error", length = 240)
    private String lastDeliveryError;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "archived_at")
    private Instant archivedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Column(name = "deleted_by_user_id")
    private UUID deletedByUserId;

    protected InternalCallNotification() {}

    public InternalCallNotification(UUID senderUserId, UUID recipientUserId, String senderName,
                                    String recipientName, String message) {
        this(senderUserId, recipientUserId, senderName, recipientName, message,
                MessagePriority.NORMAL, MessageCategory.GENERAL);
    }

    public InternalCallNotification(UUID senderUserId, UUID recipientUserId, String senderName,
                                    String recipientName, String message, MessagePriority priority,
                                    MessageCategory category) {
        this.senderUserId = senderUserId; this.recipientUserId = recipientUserId;
        this.senderName = senderName; this.recipientName = recipientName;
        this.message = message.trim().replaceAll("\\s+", " ");
        this.priority = priority == null ? MessagePriority.NORMAL : priority;
        this.category = category == null ? MessageCategory.GENERAL : category;
        this.conversationKey = conversationKey(senderUserId, recipientUserId);
        this.sentAt = Instant.now();
        this.nextDeliveryAttemptAt = this.sentAt;
    }

    public UUID getSenderUserId() { return senderUserId; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public String getSenderName() { return senderName; }
    public String getRecipientName() { return recipientName; }
    public String getMessage() { return message; }
    public MessagePriority getPriority() { return priority; }
    public MessageCategory getCategory() { return category; }
    public String getConversationKey() { return conversationKey; }
    public DeliveryStatus getDeliveryStatus() { return deliveryStatus; }
    public Instant getSentAt() { return sentAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public Instant getNextDeliveryAttemptAt() { return nextDeliveryAttemptAt; }
    public Instant getKafkaPublishedAt() { return kafkaPublishedAt; }
    public String getLastDeliveryError() { return lastDeliveryError; }
    public Instant getReadAt() { return readAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public UUID getDeletedByUserId() { return deletedByUserId; }
    public boolean isRead() { return readAt != null; }
    public void markDelivered() {
        if (deliveryStatus != DeliveryStatus.DELIVERED) {
            deliveryStatus = DeliveryStatus.DELIVERED;
            deliveredAt = Instant.now();
            nextDeliveryAttemptAt = null;
            lastDeliveryError = null;
        }
    }
    public void beginDeliveryAttempt(Instant retryAt) {
        deliveryStatus = DeliveryStatus.QUEUED;
        deliveryAttempts++;
        nextDeliveryAttemptAt = retryAt;
        lastDeliveryError = null;
    }
    public void markPublished(Instant publishedAt, Instant acknowledgementDeadline) {
        deliveryStatus = DeliveryStatus.QUEUED;
        kafkaPublishedAt = publishedAt;
        nextDeliveryAttemptAt = acknowledgementDeadline;
        lastDeliveryError = null;
    }
    public void markFailed(String error, Instant retryAt) {
        deliveryStatus = DeliveryStatus.FAILED;
        nextDeliveryAttemptAt = retryAt;
        lastDeliveryError = error == null ? "Kafka publish failed"
                : error.substring(0, Math.min(error.length(), 240));
    }
    public void markRead() {
        markDelivered();
        if (readAt == null) readAt = Instant.now();
    }
    public void archive() { if (archivedAt == null) archivedAt = Instant.now(); }
    public void softDelete(UUID actorUserId) {
        if (deletedAt == null) { deletedAt = Instant.now(); deletedByUserId = actorUserId; }
    }

    private static String conversationKey(UUID first, UUID second) {
        String left = first.toString(); String right = second.toString();
        return left.compareTo(right) <= 0 ? left + ":" + right : right + ":" + left;
    }
}
