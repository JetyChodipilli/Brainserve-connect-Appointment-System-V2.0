package com.brainserve.appointment.iam.api;

import java.util.Optional;
import java.util.UUID;

public interface TeamLeadIdentityService {
    TeamLeadIdentity promoteActiveEmployee(UUID employeeId);
    void demote(UUID userId);
    Optional<TeamLeadIdentity> activeByUserId(UUID userId);
    Optional<TeamLeadIdentity> activeByEmployeeId(UUID employeeId);

    record TeamLeadIdentity(UUID userId, UUID employeeId, String fullName, String email) {}
}
