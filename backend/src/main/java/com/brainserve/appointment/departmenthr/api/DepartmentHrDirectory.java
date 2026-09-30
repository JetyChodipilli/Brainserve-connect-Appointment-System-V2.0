package com.brainserve.appointment.departmenthr.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentHrDirectory {

    Optional<Assignment> activeForDepartment(UUID departmentId);

    Optional<Assignment> activeForUser(UUID hrUserId);

    Optional<Assignment> activeForHrEmployee(UUID hrEmployeeId);

    Assignment requireForDepartment(UUID departmentId);

    Assignment requireForUser(UUID hrUserId);

    Assignment requireAssignedReviewer(
            UUID departmentId,
            UUID hrUserId
    );

    List<Assignment> activeAssignments();

    void assignForOnboarding(
            UUID actorUserId,
            UUID departmentId,
            UUID hrUserId
    );

    void replaceForAccountClosure(
            UUID actorUserId,
            UUID closingHrUserId,
            UUID replacementHrUserId
    );

    void endForRoleTransition(
            UUID actorUserId,
            UUID hrUserId
    );

    void assignForRoleTransition(
            UUID actorUserId,
            UUID departmentId,
            UUID hrUserId,
            UUID hrEmployeeId
    );

    void transferApproved(
            UUID actorUserId,
            UUID hrUserId,
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
            UUID hrUserId,
            UUID hrEmployeeId,
            String fullName,
            String email
    ) {
    }
}
