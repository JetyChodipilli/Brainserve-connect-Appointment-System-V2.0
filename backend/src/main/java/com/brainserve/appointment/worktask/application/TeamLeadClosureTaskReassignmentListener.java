package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.teamlead.api.TeamLeadEvents;
import com.brainserve.appointment.worktask.domain.WorkTaskStatus;
import com.brainserve.appointment.worktask.infrastructure.DepartmentWorkTaskRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TeamLeadClosureTaskReassignmentListener {
    private final DepartmentWorkTaskRepository tasks;
    private final AuditService audit;

    public TeamLeadClosureTaskReassignmentListener(DepartmentWorkTaskRepository tasks, AuditService audit) {
        this.tasks = tasks;
        this.audit = audit;
    }

    @EventListener
    public void reassignOpenTasks(TeamLeadEvents.ReassignedForAccountClosure event) {
        var open = tasks.findAllByTeamLeadUserIdAndDepartmentId(event.previousTeamLeadUserId(),
                        event.departmentId()).stream()
                .filter(task -> task.getStatus() != WorkTaskStatus.APPROVED
                        && task.getStatus() != WorkTaskStatus.ACKNOWLEDGED)
                .toList();
        open.forEach(task -> task.reassignOpenTask(event.previousTeamLeadUserId(),
                event.replacementTeamLeadUserId()));
        tasks.saveAll(open);
        audit.record("WORK_TASKS_REASSIGNED_FOR_ACCOUNT_CLOSURE", "DEPARTMENT",
                event.departmentId().toString(), "{\"previousTeamLeadUserId\":\""
                        + event.previousTeamLeadUserId() + "\",\"replacementTeamLeadUserId\":\""
                        + event.replacementTeamLeadUserId() + "\",\"taskCount\":" + open.size() + "}");
    }
}
