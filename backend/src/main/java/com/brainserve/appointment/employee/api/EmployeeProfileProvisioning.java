package com.brainserve.appointment.employee.api;

import java.time.LocalDate;
import java.util.UUID;

public interface EmployeeProfileProvisioning {

    ProvisionedEmployee createAndLink(
            UUID userAccountId,
            String fullName,
            String email,
            UUID departmentId,
            String phoneNumber,
            String designation,
            LocalDate joiningDate
    );

    record ProvisionedEmployee(
            UUID employeeId
    ) {
    }
}