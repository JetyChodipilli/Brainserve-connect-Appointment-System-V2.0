package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.workinsight.api.WorkInsightEvents;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WorkInsightNotificationListener {
    private final InternalNotificationGateway notifications;

    public WorkInsightNotificationListener(
            InternalNotificationGateway notifications
    ) {
        this.notifications = notifications;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void hrAudit(WorkInsightEvents.HrAuditSubmitted event) {
        notifications.notifyManagerOfWorkInsightAudit(
                event.hrUserId(),
                event.managerUserId(),
                event.message()
        );
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void managerDecision(
            WorkInsightEvents.ManagerDecisionRecorded event
    ) {
        notifications.notifyHrOfManagerWorkInsightDecision(
                event.managerUserId(),
                event.hrUserId(),
                event.message()
        );

        if (event.approved()) {
            notifications.notifyCeoOfManagerWorkInsightApproval(
                    event.managerUserId(),
                    event.message()
            );
        }
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void ceoDecision(WorkInsightEvents.CeoDecisionRecorded event) {
        notifications.notifyHrOfWorkInsightDecision(
                event.ceoUserId(),
                event.hrUserId(),
                event.message()
        );
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void finalApproval(
            WorkInsightEvents.FinalApprovalRecipient event
    ) {
        notifications.notifyWorkerOfCeoWorkInsightApproval(
                event.ceoUserId(),
                event.recipientUserId(),
                event.message()
        );
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void reworkRequested(
            WorkInsightEvents.ReworkRequested event
    ) {
        notifications.notifyTeamLeadOfWorkInsightRework(
                event.reviewerUserId(),
                event.teamLeadUserId(),
                event.message()
        );
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void reworkAssigned(WorkInsightEvents.ReworkAssigned event) {
        notifications.sendWorkTaskUpdate(
                event.teamLeadUserId(),
                event.employeeUserId(),
                event.message()
        );
    }
}