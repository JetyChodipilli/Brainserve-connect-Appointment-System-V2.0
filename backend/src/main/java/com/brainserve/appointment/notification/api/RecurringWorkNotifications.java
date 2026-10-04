package com.brainserve.appointment.notification.api;

import java.util.UUID;

/** Persists an existing internal-call outbox message before the worksheet transaction commits. */
public interface RecurringWorkNotifications {
    void enqueue(String eventKey, UUID senderId, UUID recipientId, String senderName,
                 String recipientName, String message);
}
