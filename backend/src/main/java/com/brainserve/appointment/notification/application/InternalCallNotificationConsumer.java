package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.api.InternalCallEvent;
import com.brainserve.appointment.notification.infrastructure.InternalCallNotificationRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class InternalCallNotificationConsumer {
    private final InternalCallNotificationRepository notifications;

    public InternalCallNotificationConsumer(
            InternalCallNotificationRepository notifications
    ) {
        this.notifications = notifications;
    }

    @KafkaListener(
            topics = "${brainserve.notification.internal-call-topic}"
    )
    @Transactional
    public void receive(InternalCallEvent event) {
        int acknowledged = notifications.acknowledgeIfMatching(
                event.notificationId(),
                event.senderUserId(),
                event.recipientUserId(),
                Instant.now()
        );

        if (acknowledged == 1) {
            return;
        }

        var notification = notifications
                .findById(event.notificationId())
                .orElseThrow(() -> new BusinessException(
                        "INTERNAL_NOTIFICATION_NOT_COMMITTED",
                        "Notification row is not available for delivery",
                        HttpStatus.CONFLICT
                ));

        if (!notification.getSenderUserId()
                .equals(event.senderUserId())
                || !notification.getRecipientUserId()
                .equals(event.recipientUserId())) {
            throw new BusinessException(
                    "INTERNAL_NOTIFICATION_EVENT_MISMATCH",
                    "Notification event identity does not match the durable record",
                    HttpStatus.CONFLICT
            );
        }

        // A matching row with zero updates means Kafka redelivered an
        // event that was already acknowledged. No further update is needed.
    }
}