package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.UUID;

/** Current account, active employee/department and assignment eligibility; never JWT role claims. */
@Service
public class SearchActorScope {
    private final CurrentAccountAuthority accounts;
    private final EmployeeDirectory employees;
    private final OrganizationDirectory departments;
    public SearchActorScope(CurrentAccountAuthority accounts, EmployeeDirectory employees, OrganizationDirectory departments) {
        this.accounts = accounts; this.employees = employees; this.departments = departments;
    }
    public Scope resolve(UUID actor) {
        var authority = accounts.requireActive(actor);
        if (Set.of("ROLE_EMPLOYEE", "ROLE_TEAM_LEAD", "ROLE_HR_ADMIN", "ROLE_MANAGER").contains(authority.role())) {
            var work = accounts.requireWorkScope(actor);
            if (!authority.equals(work.authority())) throw changed();
            return new Scope(authority, work.departmentId(), work, null);
        }
        // An unlinked system administrator is eligible only for modules explicitly granting access.
        if (authority.employeeId() == null) {
            if (!authority.role().equals("ROLE_SYSTEM_ADMIN")) throw changed();
            return new Scope(authority, null, null, null);
        }
        employees.requireActiveEmployee(authority.employeeId());
        var employee = employees.employeeSummary(authority.employeeId());
        var department = departments.findDepartment(employee.departmentId()).filter(OrganizationDirectory.DepartmentSummary::active).orElseThrow(SearchActorScope::changed);
        return new Scope(authority, null, null, new Eligibility(employee.departmentId(), department.version(), employee.status()));
    }
    public void revalidate(UUID actor, Scope before) { if (!before.equals(resolve(actor))) throw changed(); }
    private static BusinessException changed() {
        return new BusinessException("SEARCH_SCOPE_CHANGED", "Your current workspace changed. Search again", HttpStatus.FORBIDDEN);
    }
    public record Eligibility(UUID departmentId, long departmentVersion, String employeeStatus) {}
    public record Scope(CurrentAccountAuthority.Authority authority, UUID departmentId, CurrentAccountAuthority.WorkScope work, Eligibility eligibility) {
        public boolean has(String permission) { return authority.permissions().contains(permission); }
        public String role() { return authority.role(); }
        public UUID employeeId() { return authority.employeeId(); }
    }
}
