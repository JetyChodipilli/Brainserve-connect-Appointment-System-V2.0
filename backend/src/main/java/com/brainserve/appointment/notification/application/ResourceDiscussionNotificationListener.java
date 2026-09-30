package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.resourcediscussion.api.ResourceDiscussionEvents;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ResourceDiscussionNotificationListener {
    private final InternalNotificationGateway notifications;
    public ResourceDiscussionNotificationListener(InternalNotificationGateway notifications) {
        this.notifications = notifications;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notify(ResourceDiscussionEvents.NotificationRequested event) {
        notifications.sendResourceDiscussionUpdate(event.senderUserId(), event.recipientUserId(), event.message());
    }
}
