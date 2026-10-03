package com.brainserve.appointment.employee.api;

import java.time.LocalDate;
import java.util.UUID;

/** Profile-only creation: importing employees never provisions credentials or invitations. */
public interface EmployeeProfileImport {
    void requireImportDepartment(UUID actorId, UUID departmentId);
    UUID importProfile(UUID actorId, Profile profile);
    record Profile(String firstName, String lastName, String officialEmail, String phoneNumber,
                   UUID departmentId, String designation, LocalDate joiningDate) {}
}
