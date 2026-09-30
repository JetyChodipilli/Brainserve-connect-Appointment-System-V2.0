package com.brainserve.appointment.notification.infrastructure;

import com.brainserve.appointment.notification.domain.InternalCallNotification;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternalCallNotificationRepository
        extends JpaRepository<InternalCallNotification, UUID> {

    List<InternalCallNotification>
    findTop100ByRecipientUserIdAndDeliveryStatusOrderBySentAtDesc(
            UUID recipientUserId,
            InternalCallNotification.DeliveryStatus deliveryStatus
    );

    List<InternalCallNotification>
    findTop100BySenderUserIdOrderBySentAtDesc(UUID senderUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select notification
              from InternalCallNotification notification
             where notification.deliveryStatus in :statuses
               and (
                    notification.nextDeliveryAttemptAt is null
                    or notification.nextDeliveryAttemptAt <= :now
               )
               and notification.deletedAt is null
             order by notification.sentAt
            """)
    List<InternalCallNotification> lockReadyForDelivery(
            @Param("statuses")
            java.util.Set<InternalCallNotification.DeliveryStatus> statuses,
            @Param("now") Instant now,
            org.springframework.data.domain.Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update internal_call_notification
               set delivery_status = 'QUEUED',
                   kafka_published_at = :publishedAt,
                   next_delivery_attempt_at = :retryAt,
                   last_delivery_error = null,
                   version = version + 1,
                   updated_at = :publishedAt,
                   updated_by = 'system'
             where id = :id
               and delivery_attempts = :deliveryAttempt
               and delivery_status <> 'DELIVERED'
            """, nativeQuery = true)
    int markPublishedIfUnacknowledged(
            @Param("id") UUID id,
            @Param("deliveryAttempt") int deliveryAttempt,
            @Param("publishedAt") Instant publishedAt,
            @Param("retryAt") Instant retryAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update internal_call_notification
               set delivery_status = 'FAILED',
                   next_delivery_attempt_at = :retryAt,
                   last_delivery_error = :error,
                   version = version + 1,
                   updated_at = :failedAt,
                   updated_by = 'system'
             where id = :id
               and delivery_attempts = :deliveryAttempt
               and delivery_status <> 'DELIVERED'
            """, nativeQuery = true)
    int markFailedIfUnacknowledged(
            @Param("id") UUID id,
            @Param("deliveryAttempt") int deliveryAttempt,
            @Param("error") String error,
            @Param("retryAt") Instant retryAt,
            @Param("failedAt") Instant failedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update internal_call_notification
               set delivery_status = 'DELIVERED',
                   delivered_at = coalesce(delivered_at, :deliveredAt),
                   next_delivery_attempt_at = null,
                   last_delivery_error = null,
                   version = version + 1,
                   updated_at = :deliveredAt,
                   updated_by = 'system'
             where id = :id
               and sender_user_id = :senderUserId
               and recipient_user_id = :recipientUserId
               and delivery_status <> 'DELIVERED'
            """, nativeQuery = true)
    int acknowledgeIfMatching(
            @Param("id") UUID id,
            @Param("senderUserId") UUID senderUserId,
            @Param("recipientUserId") UUID recipientUserId,
            @Param("deliveredAt") Instant deliveredAt
    );

    Optional<InternalCallNotification>
    findByIdAndRecipientUserIdAndDeliveryStatus(
            UUID id,
            UUID recipientUserId,
            InternalCallNotification.DeliveryStatus deliveryStatus
    );

    long countByRecipientUserIdAndDeliveryStatusAndReadAtIsNull(
            UUID recipientUserId,
            InternalCallNotification.DeliveryStatus deliveryStatus
    );

    @Query("""
            select n
              from InternalCallNotification n
             where n.recipientUserId = :userId
               and n.deliveryStatus = :status
               and n.sentAt >= :from
               and n.sentAt < :to
               and n.deletedAt is null
             order by n.sentAt desc
            """)
    List<InternalCallNotification> findInboxForDay(
            @Param("userId") UUID userId,
            @Param("status")
            InternalCallNotification.DeliveryStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("""
            select n
              from InternalCallNotification n
             where n.senderUserId = :userId
               and n.sentAt >= :from
               and n.sentAt < :to
               and n.deletedAt is null
             order by n.sentAt desc
            """)
    List<InternalCallNotification> findSentForDay(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("""
            select n
              from InternalCallNotification n
             where (
                    n.senderUserId = :userId
                    or n.recipientUserId = :userId
               )
               and n.sentAt < :before
               and n.deletedAt is null
             order by n.sentAt desc
            """)
    List<InternalCallNotification> findArchive(
            @Param("userId") UUID userId,
            @Param("before") Instant before,
            org.springframework.data.domain.Pageable pageable
    );

    Optional<InternalCallNotification>
    findByIdAndDeletedAtIsNull(UUID id);

    @Query("""
            select count(n)
              from InternalCallNotification n
             where n.recipientUserId = :userId
               and n.deliveryStatus = :status
               and n.readAt is null
               and n.deletedAt is null
               and n.sentAt >= :from
               and n.sentAt < :to
            """)
    long countTodayUnread(
            @Param("userId") UUID userId,
            @Param("status")
            InternalCallNotification.DeliveryStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}