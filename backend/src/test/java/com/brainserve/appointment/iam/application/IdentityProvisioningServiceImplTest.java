package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdentityProvisioningServiceImplTest {
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AccountProvisioningService provisioning = mock(AccountProvisioningService.class);
    private final IdentityProvisioningServiceImpl service = new IdentityProvisioningServiceImpl(users, provisioning);

    @Test
    void linksAnExistingEmployeeRegistrationToTheCreatedEmployeeProfile() {
        UUID employeeId = UUID.randomUUID();
        UserAccount account = new UserAccount("employee@brainserve.in", "Employee Name", null, "hash", false,
                AccountStatus.PENDING_HR_APPROVAL, Set.of(SystemRole.ROLE_EMPLOYEE), null);
        when(users.findByEmailIgnoreCase("employee@brainserve.in")).thenReturn(Optional.of(account));

        service.createEmployeeAccount(employeeId, "Employee Name", "employee@brainserve.in",
                "UnusedTemporaryPassword1!", UUID.randomUUID());

        assertThat(account.getEmployeeId()).isEqualTo(employeeId);
        verifyNoInteractions(provisioning);
    }

    @Test
    void linksAnApprovedEmployeeLoginWhenHrAssignsItsDepartmentAfterApproval() {
        UUID employeeId = UUID.randomUUID();
        UserAccount approved = new UserAccount("approved.employee@brainserve.in", "Approved Employee", null,
                "hash", false, AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_EMPLOYEE), null);
        when(users.findByEmailIgnoreCase("approved.employee@brainserve.in")).thenReturn(Optional.of(approved));

        service.createEmployeeAccount(employeeId, "Approved Employee", "approved.employee@brainserve.in",
                "UnusedTemporaryPassword1!", UUID.randomUUID());

        assertThat(approved.getEmployeeId()).isEqualTo(employeeId);
        verify(users).saveAndFlush(approved);
        verifyNoInteractions(provisioning);
    }

    @Test
    void provisionsALinkedEmployeeLoginWhenNoRegistrationExists() {
        UUID employeeId = UUID.randomUUID(); UUID actorId = UUID.randomUUID();
        when(users.findByEmailIgnoreCase("employee@brainserve.in")).thenReturn(Optional.empty());

        service.createEmployeeAccount(employeeId, "Employee Name", "employee@brainserve.in",
                "TemporaryPassword1!", actorId);

        verify(provisioning).createForHrApproval(actorId, "Employee Name", "employee@brainserve.in",
                "TemporaryPassword1!", SystemRole.ROLE_EMPLOYEE, employeeId);
    }
}
