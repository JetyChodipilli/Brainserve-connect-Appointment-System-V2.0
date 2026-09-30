package com.brainserve.appointment.resourcediscussion.api;

import java.util.UUID;

public final class ResourceDiscussionEvents {
    private ResourceDiscussionEvents() {}
    public record NotificationRequested(UUID senderUserId, UUID recipientUserId, String message) {}
}
