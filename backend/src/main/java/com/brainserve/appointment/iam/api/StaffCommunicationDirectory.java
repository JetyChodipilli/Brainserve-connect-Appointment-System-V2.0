package com.brainserve.appointment.iam.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface StaffCommunicationDirectory {
    StaffMember requireActive(UUID userId);
    java.util.Optional<StaffMember> findByUserId(UUID userId);
    List<StaffMember> findByUserIds(Set<UUID> userIds);
    java.util.Optional<StaffMember> activeByEmployeeId(UUID employeeId);
    List<StaffMember> activeWithAnyRole(Set<String> roles);
    List<StaffMember> activeWithAnyRoleInDepartment(Set<String> roles, UUID departmentId, int limit);
    StaffMember requireChiefExecutive();

    record StaffMember(UUID userId, UUID employeeId, String fullName, String email, Set<String> roles) {}
}
