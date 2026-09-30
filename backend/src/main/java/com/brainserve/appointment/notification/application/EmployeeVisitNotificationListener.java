package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.appointment.api.AppointmentEvents;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class EmployeeVisitNotificationListener {
    private final InternalCallNotificationService notifications;

    public EmployeeVisitNotificationListener(InternalCallNotificationService notifications) {
        this.notifications = notifications;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void visitorCardUpdated(AppointmentEvents.EmployeeVisitCardUpdated event) {
        notifications.notifyEmployeeOfVisitorCard(event.actorUserId(), event.hostEmployeeId(), event.reference(),
                event.visitorName(), event.visitorEmail(), event.visitorPhone(), event.visitorCompany(),
                event.purpose(), event.slotStart(), event.status());
    }
}
