package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.notification.api.PolicyNotifications;
import com.brainserve.appointment.notification.domain.InternalCallNotification;
import com.brainserve.appointment.notification.infrastructure.InternalCallNotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class PolicyNotificationService implements PolicyNotifications {
    private final StaffCommunicationDirectory staff;
    private final InternalCallNotificationRepository notifications;
    public PolicyNotificationService(StaffCommunicationDirectory staff, InternalCallNotificationRepository notifications) {
        this.staff=staff; this.notifications=notifications;
    }
    @Override @Transactional
    public UUID enqueue(UUID sender, UUID recipient, String message) {
        var from=staff.requireActive(sender); var to=staff.requireActive(recipient);
        return notifications.saveAndFlush(new InternalCallNotification(sender,recipient,from.fullName(),to.fullName(),message,
                InternalCallNotification.MessagePriority.HIGH,InternalCallNotification.MessageCategory.ESCALATION)).getId();
    }
}
