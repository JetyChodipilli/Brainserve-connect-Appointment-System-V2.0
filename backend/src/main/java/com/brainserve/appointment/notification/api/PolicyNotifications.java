package com.brainserve.appointment.notification.api;
import java.util.UUID;

/** Durable mandatory notice; callers must resolve current eligibility before enqueuing. */
public interface PolicyNotifications {
    UUID enqueue(UUID sender, UUID recipient, String message);
}
