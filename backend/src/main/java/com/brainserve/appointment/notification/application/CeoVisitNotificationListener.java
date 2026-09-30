package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.appointment.api.AppointmentEvents;
import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CeoVisitNotificationListener {
    private final InternalNotificationGateway notifications;

    public CeoVisitNotificationListener(InternalNotificationGateway notifications) {
        this.notifications = notifications;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void managerApproved(AppointmentEvents.ManagerApprovalRequested event) {
        notifications.notifyCeoOfManagerVisitApproval(event.managerUserId(),
                "CEO visit " + event.reference() + " for " + event.visitorName()
                        + " was approved by the department Manager. Purpose: " + event.purpose()
                        + ". Your final decision is required.");
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void ceoDecided(AppointmentEvents.CeoVisitDecisionRecorded event) {
        String outcome = event.approved() ? "approved" : "rejected";
        String remarks = event.remarks() == null || event.remarks().isBlank()
                ? "" : " Remarks: " + event.remarks().trim();
        notifications.notifyManagerOfCeoVisitDecision(event.ceoUserId(), event.routingDepartmentId(),
                "CEO visit " + event.reference() + " was " + outcome + " by the CEO." + remarks);
    }
}
