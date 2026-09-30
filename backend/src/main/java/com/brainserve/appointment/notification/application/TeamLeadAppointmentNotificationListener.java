package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.appointment.api.AppointmentEvents;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TeamLeadAppointmentNotificationListener {
    private final InternalCallNotificationService notifications;
    public TeamLeadAppointmentNotificationListener(InternalCallNotificationService notifications) {
        this.notifications = notifications;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void routed(AppointmentEvents.TeamLeadApprovalRequested event) {
        notifications.notifyTeamLeadOfAppointment(event.hrUserId(), event.teamLeadUserId(), event.reference(),
                event.visitorName(), event.purpose());
    }
}
