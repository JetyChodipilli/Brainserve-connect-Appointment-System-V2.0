package com.brainserve.appointment.worktask.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Canonical worksheet creation with its initial checklist and durable notification in one transaction. */
public interface ScheduledWorkMaterializer {
    Workspace routineWorkspace(UUID actor);
    void validateScheduled(UUID actor, Command command);
    UUID createScheduled(UUID actor, Command command);
    record Checklist(String title, boolean required) {}
    record EligibleAssignee(UUID employeeId, String displayName, String role) {}
    record Workspace(UUID departmentId, String departmentName, List<EligibleAssignee> eligibleAssignees) {}
    record Command(UUID departmentId, UUID employeeId, String assigneeRule, String title,
                   String instructions, LocalDate dueDate, List<Checklist> checklist, String eventKey) {}
}
