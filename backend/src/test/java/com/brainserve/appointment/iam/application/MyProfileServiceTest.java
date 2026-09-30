package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.document.api.ProfilePhotoStore;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyProfileServiceTest {
    private final StaffCommunicationDirectory staff = mock(StaffCommunicationDirectory.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrganizationDirectory organization = mock(OrganizationDirectory.class);
    private final ProfilePhotoStore photos = mock(ProfilePhotoStore.class);
    private final MyProfileService service = new MyProfileService(staff, employees, organization, photos);

    @Test
    void combinesTheAuthenticatedAccountWithItsEmployeeAndDepartmentAssignment() {
        UUID userId = UUID.randomUUID(); UUID employeeId = UUID.randomUUID(); UUID departmentId = UUID.randomUUID();
        when(staff.requireActive(userId)).thenReturn(new StaffCommunicationDirectory.StaffMember(
                userId, employeeId, "Riya Sharma", "riya@brainserve.in", Set.of("ROLE_TEAM_LEAD")));
        when(employees.employeeSummary(employeeId)).thenReturn(new EmployeeDirectory.EmployeeSummary(
                employeeId, "BSPL-IT-0042", "Riya Sharma", "riya@brainserve.in", departmentId,
                "Engineering Lead", "ACTIVE"));
        when(organization.findDepartment(departmentId)).thenReturn(Optional.of(
                new OrganizationDirectory.DepartmentSummary(departmentId, "TECH", "Technology", true, 0)));
        when(photos.current(userId)).thenReturn(Optional.empty());

        var result = service.profile(userId);

        assertThat(result.fullName()).isEqualTo("Riya Sharma");
        assertThat(result.roles()).containsExactly("ROLE_TEAM_LEAD");
        assertThat(result.employeeNumber()).isEqualTo("BSPL-IT-0042");
        assertThat(result.departmentName()).isEqualTo("Technology");
        assertThat(result.photoUrl()).isNull();
    }
}
