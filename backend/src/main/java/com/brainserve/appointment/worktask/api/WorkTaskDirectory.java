package com.brainserve.appointment.worktask.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public read/write boundary used by other modules that need worksheet state.
 * Domain entities and repositories remain private to the worktask module.
 */
public interface WorkTaskDirectory {

    List<TaskSnapshot> recentForDepartment(UUID departmentId);

    TaskSnapshot requireTask(UUID workTaskId);

    TaskSnapshot requestInsightRework(
            UUID workTaskId,
            String reviewerRole,
            String reason
    );

    TaskSnapshot assignInsightRework(
            UUID workTaskId,
            String guidance
    );

    TaskSnapshot reviseInsightReworkSubmission(
            UUID teamLeadUserId,
            UUID workTaskId,
            String update
    );

    TaskSnapshot finalizeInsightApproval(UUID workTaskId);

    record TaskSnapshot(
            UUID id,
            Instant createdAt,
            UUID departmentId,
            String departmentBranch,
            UUID employeeId,
            UUID teamLeadUserId,
            UUID assignedByUserId,
            String assignedByRole,
            String assigneeRole,
            String title,
            String status
    ) {
    }
}
