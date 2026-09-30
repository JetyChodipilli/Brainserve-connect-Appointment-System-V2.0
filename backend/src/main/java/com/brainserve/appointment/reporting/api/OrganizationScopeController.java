package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/departments")
public class OrganizationScopeController {
    private final OrganizationDirectory organization;
    private final DepartmentHrDirectory departmentHrs;
    private final TeamLeadDirectory teamLeads;
    private final ManagerDirectory managers;
    private final EmployeeDirectory employees;

    public OrganizationScopeController(
            OrganizationDirectory organization,
            DepartmentHrDirectory departmentHrs,
            TeamLeadDirectory teamLeads,
            ManagerDirectory managers,
            EmployeeDirectory employees
    ) {
        this.organization = organization;
        this.departmentHrs = departmentHrs;
        this.teamLeads = teamLeads;
        this.managers = managers;
        this.employees = employees;
    }

    @GetMapping("/visible")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN','MANAGER','TEAM_LEAD','EMPLOYEE')")
    List<OrganizationDirectory.DepartmentSummary> visible(@AuthenticationPrincipal Jwt jwt) {
        List<String> authorities = jwt.getClaimAsStringList("authorities");
        if (authorities != null && authorities.contains("ROLE_CEO")) {
            return organization.allDepartments();
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        UUID departmentId;

        if (authorities != null && authorities.contains("ROLE_HR_ADMIN")) {
            departmentId = departmentHrs.requireForUser(userId).departmentId();
        } else if (authorities != null && authorities.contains("ROLE_MANAGER")) {
            departmentId = managers.requireForUser(userId).departmentId();
        } else if (authorities != null && authorities.contains("ROLE_TEAM_LEAD")) {
            departmentId = teamLeads.requireForUser(userId).departmentId();
        } else if (authorities != null && authorities.contains("ROLE_EMPLOYEE")) {
            String employeeId = jwt.getClaimAsString("employeeId");
            if (employeeId == null || employeeId.isBlank()) {
                throw new BusinessException(
                        "EMPLOYEE_PROFILE_NOT_LINKED",
                        "This Employee login is not linked to an employee profile",
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }
            departmentId = employees.departmentIdForEmployee(UUID.fromString(employeeId));
        } else {
            throw new BusinessException(
                    "DEPARTMENT_SCOPE_NOT_AVAILABLE",
                    "A department-scoped role is required",
                    HttpStatus.FORBIDDEN
            );
        }

        return organization.findDepartment(departmentId).stream().toList();
    }

    @GetMapping("/leadership")
    @PreAuthorize("hasAnyRole('CEO','HR_ADMIN','MANAGER','TEAM_LEAD','EMPLOYEE')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    List<DepartmentLeadership> leadership(@AuthenticationPrincipal Jwt jwt) {
        // Resolve scope from the authenticated actor, never from client-supplied IDs.
        return visible(jwt).stream().map(department -> new DepartmentLeadership(
                department.id(),
                teamLeads.activeForDepartment(department.id())
                        .map(assignment -> new Leader(assignment.fullName())).orElse(null),
                departmentHrs.activeForDepartment(department.id())
                        .map(assignment -> new Leader(assignment.fullName())).orElse(null),
                managers.activeForDepartment(department.id())
                        .map(assignment -> new Leader(assignment.fullName())).orElse(null)
        )).toList();
    }

    record Leader(String fullName) {}
    record DepartmentLeadership(UUID departmentId, Leader teamLead, Leader hr, Leader manager) {}
}
