package com.brainserve.appointment.notification.api;

import java.time.Instant;
import java.util.UUID;

public record InternalCallEvent(UUID notificationId, UUID senderUserId, UUID recipientUserId,
                                String message, Instant sentAt) {}
