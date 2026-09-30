package com.brainserve.appointment.manager.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManagerDirectory {
    Optional<Assignment> activeForDepartment(UUID departmentId);
    Optional<Assignment> activeForUser(UUID managerUserId);
    Assignment requireForDepartment(UUID departmentId);
    Assignment requireForUser(UUID managerUserId);
    Assignment requireAssignedReviewer(UUID departmentId, UUID managerUserId);
    List<Assignment> activeAssignments();
    void assignForOnboarding(UUID actorUserId, UUID departmentId, UUID managerUserId);
    void replaceForAccountClosure(UUID actorUserId, UUID closingManagerUserId,
                                  UUID replacementManagerUserId);
    void endForRoleTransition(UUID actorUserId, UUID managerUserId);
    void assignForRoleTransition(UUID actorUserId, UUID departmentId, UUID managerUserId,
                                 UUID managerEmployeeId);

    record Assignment(UUID assignmentId, UUID departmentId, UUID managerUserId,
                      UUID managerEmployeeId, String fullName, String email) {}
}
