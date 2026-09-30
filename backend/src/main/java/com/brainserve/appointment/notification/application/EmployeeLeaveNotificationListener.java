package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.employee.api.EmployeeLeaveEvents;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class EmployeeLeaveNotificationListener {
    private final InternalCallNotificationService service;
    public EmployeeLeaveNotificationListener(InternalCallNotificationService service) { this.service = service; }
    @Async("notificationExecutor") @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requested(EmployeeLeaveEvents.LeaveRequested event) {
        service.notifyHrOfLeaveRequest(event.employeeUserId(), event.departmentId(), event.employeeName(),
                event.startDate(), event.endDate(), event.reason());
    }
    @Async("notificationExecutor") @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void decided(EmployeeLeaveEvents.LeaveDecided event) {
        service.notifyEmployeeOfLeaveDecision(event.decidedByUserId(), event.employeeUserId(), event.decision(),
                event.startDate(), event.endDate());
    }
}
