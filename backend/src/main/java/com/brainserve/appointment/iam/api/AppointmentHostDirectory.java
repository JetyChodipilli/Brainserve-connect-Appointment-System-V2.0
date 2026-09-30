package com.brainserve.appointment.iam.api;

import java.util.List;
import java.util.UUID;

/** IAM information required to expose privileged staff as appointment hosts. */
public interface AppointmentHostDirectory {
    List<HostAccount> activeChiefExecutives();
    List<HostAccount> activeHumanResourcesAdministrators();
    boolean isAccountEligible(UUID employeeId);
    String categoryForEmployee(UUID employeeId);
    void linkEmployee(UUID userId, UUID employeeId);

    record HostAccount(UUID userId, UUID employeeId, String fullName, String email, String category) {}
}
