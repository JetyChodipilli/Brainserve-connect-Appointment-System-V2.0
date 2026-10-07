package com.brainserve.appointment.worktask.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public read/write boundary used by other modules that need worksheet state.
 * Domain entities and repositories remain private to the worktask module.
 */
public interface WorkTaskDirectory {

    default void reviewDelivery(UUID actor,UUID taskId,boolean approved,String remarks,Long expectedVersion) {
        throw new UnsupportedOperationException("Delivery review requires the production worktask service");
    }

    List<TaskSnapshot> recentForDepartment(UUID departmentId);

    TaskSnapshot requireTask(UUID workTaskId);

    /** Serializes the existing Insight writer with delivery writers and advances the observed revision. */
    TaskSnapshot requireTaskForMutation(UUID workTaskId, Long expectedVersion);

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

    default void acceptHrDeliveryEvidence(UUID workTaskId) {}

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
