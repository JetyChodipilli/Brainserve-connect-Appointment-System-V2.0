package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.appointment.api.AppointmentEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SecurityArrivalNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(SecurityArrivalNotificationListener.class);
    private final InternalCallNotificationService notifications;

    public SecurityArrivalNotificationListener(InternalCallNotificationService notifications) {
        this.notifications = notifications;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void securityIntakeRecorded(AppointmentEvents.SecurityIntakeRecorded event) {
        try {
            notifications.sendSecurityArrival(event.securityActorUserId(), event.reference(),
                    event.visitorName(), event.purpose());
        } catch (RuntimeException exception) {
            log.error("Security arrival Kafka notification failed appointmentId={} reference={}",
                    event.appointmentId(), event.reference(), exception);
        }
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void receptionForwarded(AppointmentEvents.ReceptionForwarded event) {
        try {
            notifications.sendReceptionForward(event.receptionistUserId(), event.reference(),
                    event.hostEmployeeId(), event.appointmentType(), event.visitorName(), event.remarks());
        } catch (RuntimeException exception) {
            log.error("Reception forward Kafka notification failed appointmentId={} reference={}",
                    event.appointmentId(), event.reference(), exception);
        }
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void receptionVerified(AppointmentEvents.ReceptionVerified event) {
        try {
            notifications.sendReceptionVerification(event.receptionistUserId(), event.hostEmployeeId(),
                    event.routingDepartmentId(), event.appointmentType(), event.reference(), event.visitorName(), event.purpose());
        } catch (RuntimeException exception) {
            log.error("Reception verification Kafka notification failed appointmentId={} reference={}",
                    event.appointmentId(), event.reference(), exception);
        }
    }
}
