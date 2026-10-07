package com.brainserve.appointment.notification.api;
import java.util.UUID;
/** Durable handover notice in the caller's assignment transaction. */
public interface HandoverNotifications {
    void enqueue(String eventKey,UUID senderId,UUID recipientId,String senderName,String recipientName,String message);
}
