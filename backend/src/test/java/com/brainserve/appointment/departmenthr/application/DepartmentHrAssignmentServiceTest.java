package com.brainserve.appointment.departmenthr.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.domain.DepartmentHrAssignment;
import com.brainserve.appointment.departmenthr.infrastructure.DepartmentHrAssignmentRepository;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepartmentHrAssignmentServiceTest {
    private final DepartmentHrAssignmentRepository assignments = mock(DepartmentHrAssignmentRepository.class);
    private final OrganizationDirectory organization = mock(OrganizationDirectory.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final StaffCommunicationDirectory staff = mock(StaffCommunicationDirectory.class);
    private final AuditService audit = mock(AuditService.class);
    private final DepartmentHrAssignmentService service = new DepartmentHrAssignmentService(
            assignments, organization, employees, staff, audit);

    @Test
    void transfersAnExistingHrToTheNewDepartmentAndKeepsOneActiveAssignment() {
        UUID actorId = UUID.randomUUID(); UUID hrUserId = UUID.randomUUID(); UUID employeeId = UUID.randomUUID();
        UUID previousDepartmentId = UUID.randomUUID(); UUID targetDepartmentId = UUID.randomUUID();
        var previous = new DepartmentHrAssignment(previousDepartmentId, hrUserId, employeeId, actorId);
        when(organization.lockActiveDepartment(targetDepartmentId))
                .thenReturn(new OrganizationDirectory.ActiveDepartment(targetDepartmentId, "PRODUCT", "Product"));
        when(staff.requireActive(hrUserId)).thenReturn(new StaffCommunicationDirectory.StaffMember(
                hrUserId, employeeId, "Department HR", "hr@brainserve.in", Set.of("ROLE_HR_ADMIN")));
        when(assignments.findByDepartmentIdAndActiveTrue(targetDepartmentId)).thenReturn(Optional.empty());
        when(assignments.findByHrUserIdAndActiveTrue(hrUserId)).thenReturn(Optional.of(previous));
        when(assignments.saveAndFlush(any(DepartmentHrAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DepartmentHrAssignment created = service.assign(actorId, targetDepartmentId, hrUserId);

        assertThat(previous.isActive()).isFalse();
        assertThat(created.getDepartmentId()).isEqualTo(targetDepartmentId);
        assertThat(created.getHrUserId()).isEqualTo(hrUserId);
        verify(employees).transferDepartment(employeeId, targetDepartmentId);
        verify(audit).record(org.mockito.ArgumentMatchers.eq("DEPARTMENT_HR_ASSIGNED"),
                org.mockito.ArgumentMatchers.eq("DEPARTMENT"), org.mockito.ArgumentMatchers.eq(targetDepartmentId.toString()),
                org.mockito.ArgumentMatchers.contains(previousDepartmentId.toString()));
    }
}
