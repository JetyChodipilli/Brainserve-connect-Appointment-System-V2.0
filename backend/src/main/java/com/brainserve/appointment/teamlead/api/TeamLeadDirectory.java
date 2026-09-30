package com.brainserve.appointment.teamlead.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamLeadDirectory {

    Optional<Assignment> activeForHost(UUID hostEmployeeId);

    Optional<Assignment> activeForDepartment(UUID departmentId);

    Optional<Assignment> activeForUser(UUID teamLeadUserId);

    Assignment requireAssignedForHost(
            UUID teamLeadUserId,
            UUID hostEmployeeId
    );

    Assignment requireForUser(UUID teamLeadUserId);

    List<Assignment> activeAssignments();

    void endForEmployeeIfAssigned(
            UUID actorUserId,
            UUID employeeId
    );

    void replaceForAccountClosure(
            UUID actorUserId,
            UUID closingTeamLeadUserId,
            UUID replacementEmployeeId
    );

    void endForRoleTransition(
            UUID actorUserId,
            UUID teamLeadUserId
    );

    void assignForRoleTransition(
            UUID actorUserId,
            UUID departmentId,
            UUID teamLeadUserId,
            UUID teamLeadEmployeeId
    );

    void transferApproved(
            UUID actorUserId,
            UUID teamLeadUserId,
            UUID targetDepartmentId,
            TransferResolution resolution
    );

    enum TransferResolution {
        MOVE,
        REPLACE,
        SWAP
    }

    record Assignment(
            UUID assignmentId,
            UUID departmentId,
            UUID teamLeadUserId,
            UUID teamLeadEmployeeId,
            String fullName,
            String email
    ) {
    }
}
