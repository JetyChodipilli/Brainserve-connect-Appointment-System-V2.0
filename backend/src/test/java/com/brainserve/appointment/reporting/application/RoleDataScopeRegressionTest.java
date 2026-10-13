package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleDataScopeRegressionTest {
    private final StaffCommunicationDirectory staff = mock(StaffCommunicationDirectory.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final RoleDataScopeService service = new RoleDataScopeService(staff, employees);
    private final UUID actor = UUID.randomUUID();

    // Regression: AUD-04 — missing department scope must never become global reporting access.
    // Found by /qa on 2026-10-13
    // Report: docs/SECURITY_FUNCTIONAL_CAPACITY_AUDIT.md
    @ParameterizedTest
    @ValueSource(strings = {"ROLE_HR_ADMIN", "ROLE_MANAGER", "ROLE_TEAM_LEAD", "ROLE_EMPLOYEE"})
    void anUnlinkedDepartmentAccountCannotResolveAReportingScope(String role) {
        when(staff.requireActive(actor)).thenReturn(member(null, role));
        assertThatThrownBy(() -> service.resolve(actor)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("HISTORY_SCOPE_DENIED");
    }

    @Test void aMissingDepartmentCannotResolveAReportingScope() {
        UUID employee = UUID.randomUUID();
        when(staff.requireActive(actor)).thenReturn(member(employee, "ROLE_HR_ADMIN"));
        when(employees.departmentIdForEmployee(employee)).thenReturn(null);
        assertThatThrownBy(() -> service.resolve(actor)).isInstanceOf(BusinessException.class);
    }

    @Test void anExplicitCompanyRoleCanRemainUnlinked() {
        when(staff.requireActive(actor)).thenReturn(member(null, "ROLE_SYSTEM_ADMIN"));
        assertThat(service.resolve(actor).organizationWide()).isTrue();
    }

    @Test void aLinkedHrAccountResolvesOnlyItsDepartment() {
        UUID employee = UUID.randomUUID(), department = UUID.randomUUID();
        when(staff.requireActive(actor)).thenReturn(member(employee, "ROLE_HR_ADMIN"));
        when(employees.departmentIdForEmployee(employee)).thenReturn(department);
        var scope = service.resolve(actor);
        assertThat(scope.organizationWide()).isFalse();
        assertThat(service.effectiveDepartment(scope, null)).isEqualTo(department);
    }

    private StaffCommunicationDirectory.StaffMember member(UUID employee, String role) {
        return new StaffCommunicationDirectory.StaffMember(actor, employee, "Synthetic", "synthetic@audit.invalid", Set.of(role));
    }
}
