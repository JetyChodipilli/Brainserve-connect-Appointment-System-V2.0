package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.worktask.api.WorkTaskEvents;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WorkTaskNotificationListener {
    private final InternalNotificationGateway notifications;
    public WorkTaskNotificationListener(InternalNotificationGateway notifications) {
        this.notifications = notifications;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void comment(com.brainserve.appointment.worktask.api.TaskActivityController.CommentNotificationRequested event) {
        try { notifications.sendTaskCommentUpdate(event.senderUserId(),event.recipientUserId(),event.taskId(),event.commentId()); }
        catch (com.brainserve.appointment.shared.application.BusinessException denied) {
            // Authority may be removed between commit and asynchronous delivery. No comment text was sent.
            org.slf4j.LoggerFactory.getLogger(WorkTaskNotificationListener.class).info("Comment notification no longer authorized for worksheet {}",event.taskId());
        }
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void direct(WorkTaskEvents.DirectNotificationRequested event) {
        notifications.sendWorkTaskUpdate(event.senderUserId(), event.recipientUserId(), event.message());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void hrUpdate(WorkTaskEvents.HrNotificationRequested event) {
        notifications.notifyHrOfWorkTaskUpdate(event.actorUserId(), event.departmentId(), event.message());
    }
}
