package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.Permission;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepartmentScopedAdministrationServiceTest {
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final CompanyEmailPolicy emails = mock(CompanyEmailPolicy.class);
    private final EmailService emailService = mock(EmailService.class);
    private final AuditService audit = mock(AuditService.class);
    private final DepartmentHrDirectory departmentHrs = mock(DepartmentHrDirectory.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);

    @Test
    void hrCannotDisableAnEmployeeAccountFromAnotherDepartment() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID actorDepartmentId = UUID.randomUUID();
        UUID targetEmployeeId = UUID.randomUUID();
        UserAccount actor = account(actorId, null, Set.of(SystemRole.ROLE_HR_ADMIN));
        UserAccount target = account(targetId, targetEmployeeId, Set.of(SystemRole.ROLE_EMPLOYEE));
        when(users.findById(actorId)).thenReturn(Optional.of(actor));
        when(users.findById(targetId)).thenReturn(Optional.of(target));
        when(departmentHrs.requireForUser(actorId)).thenReturn(new DepartmentHrDirectory.Assignment(
                UUID.randomUUID(), actorDepartmentId, actorId, UUID.randomUUID(), "HR", "hr@brainserve.in"));
        when(employees.departmentIdForEmployee(targetEmployeeId)).thenReturn(UUID.randomUUID());
        var service = new StaffAccountAdministrationService(users, passwords, emails, emailService,
                audit, departmentHrs, employees);

        assertThatThrownBy(() -> service.setEnabled(actorId, targetId, false))
                .isInstanceOf(BusinessException.class)
                .extracting(reason -> ((BusinessException) reason).getErrorCode())
                .isEqualTo("STAFF_ACCOUNT_DEPARTMENT_SCOPE_DENIED");
    }

    @Test
    void hrCanManagePermissionOverridesForATeamLeadAccountInTheirDepartment() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID targetEmployeeId = UUID.randomUUID();
        UserAccount actor = account(actorId, null, Set.of(SystemRole.ROLE_HR_ADMIN));
        UserAccount target = account(targetId, targetEmployeeId,
                Set.of(SystemRole.ROLE_TEAM_LEAD));
        when(users.findById(actorId)).thenReturn(Optional.of(actor));
        when(users.findById(targetId)).thenReturn(Optional.of(target));
        when(departmentHrs.requireForUser(actorId)).thenReturn(new DepartmentHrDirectory.Assignment(
                UUID.randomUUID(), departmentId, actorId, UUID.randomUUID(), "HR", "hr@brainserve.in"));
        when(employees.departmentIdForEmployee(targetEmployeeId)).thenReturn(departmentId);
        var service = new PermissionAdministrationService(users, audit, departmentHrs, employees);

        service.replaceOverrides(actorId, targetId, Set.of(Permission.WORK_TASK_READ), Set.of());

        verify(audit).record("USER_PERMISSION_OVERRIDE", "USER_ACCOUNT", targetId.toString(),
                "{\"grants\":1,\"denies\":0}");
    }

    private UserAccount account(UUID id, UUID employeeId, Set<SystemRole> roles) {
        UserAccount account = new UserAccount("user-" + id + "@brainserve.in", "Scoped user", employeeId,
                "encoded-password", false, AccountStatus.ACTIVE, roles, null);
        assignId(account, id);
        return account;
    }

    private static void assignId(AuditableEntity entity, UUID id) {
        try {
            Field field = AuditableEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
