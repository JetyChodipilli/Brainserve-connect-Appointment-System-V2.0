package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrganizationLeadershipTest {
    @Test
    void everyDepartmentRoleReceivesAllThreeLeadersOnlyWithinItsScope() {
        for (String role : List.of("HR_ADMIN", "MANAGER", "TEAM_LEAD", "EMPLOYEE")) {
            var organization = mock(OrganizationDirectory.class);
            var hrs = mock(DepartmentHrDirectory.class);
            var leads = mock(TeamLeadDirectory.class);
            var managers = mock(ManagerDirectory.class);
            var employees = mock(EmployeeDirectory.class);
            UUID user = UUID.randomUUID(), employee = UUID.randomUUID(), department = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            var hr = new DepartmentHrDirectory.Assignment(UUID.randomUUID(), department, user, employee, "HR Name", "hr@example.com");
            var lead = new TeamLeadDirectory.Assignment(UUID.randomUUID(), department, user, employee, "Lead Name", "lead@example.com");
            var manager = new ManagerDirectory.Assignment(UUID.randomUUID(), department, user, employee, "Manager Name", "manager@example.com");
            when(hrs.requireForUser(user)).thenReturn(hr);
            when(leads.requireForUser(user)).thenReturn(lead);
            when(managers.requireForUser(user)).thenReturn(manager);
            when(employees.departmentIdForEmployee(employee)).thenReturn(department);
            when(organization.findDepartment(department)).thenReturn(Optional.of(
                    new OrganizationDirectory.DepartmentSummary(department, "HR", "Human Resources", true, 0)));
            when(hrs.activeForDepartment(department)).thenReturn(Optional.of(hr));
            when(leads.activeForDepartment(department)).thenReturn(Optional.of(lead));
            when(managers.activeForDepartment(department)).thenReturn(Optional.of(manager));
            var controller = new OrganizationScopeController(organization, hrs, leads, managers, employees);
            var jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString())
                    .claim("authorities", List.of("ROLE_" + role)).claim("employeeId", employee.toString()).build();
            var rows = controller.leadership(jwt);
            assertEquals(1, rows.size());
            assertEquals(department, rows.getFirst().departmentId());
            assertEquals("HR Name", rows.getFirst().hr().fullName());
            assertEquals("Lead Name", rows.getFirst().teamLead().fullName());
            assertEquals("Manager Name", rows.getFirst().manager().fullName());
            verify(organization, never()).allDepartments();
            verify(hrs, never()).activeForDepartment(other);
            verify(leads, never()).activeForDepartment(other);
            verify(managers, never()).activeForDepartment(other);
            when(leads.activeForDepartment(department)).thenReturn(Optional.empty());
            assertNull(controller.leadership(jwt).getFirst().teamLead());
        }
    }
}
