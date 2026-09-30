package com.brainserve.appointment.employee.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.util.UUID;

public interface EmployeeDirectory {
    void requireEmployee(UUID employeeId);
    void requireActiveEmployee(UUID employeeId);
    void requireActiveHost(UUID employeeId);
    HostCategory hostCategory(UUID employeeId);
    UUID departmentIdForEmployee(UUID employeeId);
    void transferDepartment(UUID employeeId, UUID departmentId);
    void transitionOperationalPosition(UUID employeeId, UUID departmentId, String designation);
    void deactivateForAccountArchive(UUID employeeId);
    void restoreAfterAccountRecovery(UUID employeeId, UUID departmentId, String designation);
    void terminateAfterApproval(UUID employeeId, LocalDate effectiveDate);
    boolean isChiefExecutive(UUID employeeId);
    EmployeeSummary employeeSummary(UUID employeeId);
    Map<UUID, EmployeeSummary> employeeSummaries(Set<UUID> employeeIds);
    Page<DepartmentMember> departmentMembers(UUID departmentId, Pageable pageable);
    Page<PublicEmployee> publicActiveEmployees(UUID departmentId, String query, Pageable pageable);
    List<HostSummary> activeHosts();

    enum HostCategory { CEO, HR, TEAM_LEAD, EMPLOYEE }
    record HostSummary(UUID id, String displayName, String designation, UUID departmentId,
                       String departmentName, HostCategory category) {}
    record DepartmentMember(UUID id, String employeeNumber, String displayName, String officialEmail,
                            UUID departmentId, String designation, String status) {}
    record EmployeeSummary(UUID id, String employeeNumber, String displayName, String officialEmail,
                           UUID departmentId, String designation, String status) {}
    record PublicEmployee(UUID id, String displayName, String designation, UUID departmentId) {}
}
