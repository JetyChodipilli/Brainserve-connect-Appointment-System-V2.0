package com.brainserve.appointment.iam.api;

import java.util.UUID;

public interface IdentityProvisioningService {
    void createEmployeeAccount(UUID employeeId, String fullName, String officialEmail,
                               String temporaryPassword, UUID createdByUserId);
    void disableEmployeeAccount(UUID employeeId);
}
