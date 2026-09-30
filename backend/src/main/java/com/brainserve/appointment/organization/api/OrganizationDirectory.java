package com.brainserve.appointment.organization.api;

import java.util.UUID;
import java.util.Optional;
import java.util.List;

public interface OrganizationDirectory {
    ActiveDepartment requireActiveDepartment(UUID departmentId);
    ActiveDepartment lockActiveDepartment(UUID departmentId);
    ActiveDepartment requireActiveDepartmentByCode(String code);
    Optional<DepartmentSummary> findDepartment(UUID departmentId);
    List<DepartmentSummary> allDepartments();
    record ActiveDepartment(UUID id, String code, String name) {}
    record DepartmentSummary(UUID id, String code, String name, boolean active, long version) {}
}
