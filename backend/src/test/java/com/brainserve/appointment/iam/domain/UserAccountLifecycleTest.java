package com.brainserve.appointment.iam.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class UserAccountLifecycleTest {
    @Test
    void accountMustHaveExactlyOneEffectiveRole() {
        assertThatIllegalArgumentException().isThrownBy(() -> new UserAccount(
                "mixed@brainserve.in", "Mixed role", UUID.randomUUID(), "hash", false,
                AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_EMPLOYEE, SystemRole.ROLE_TEAM_LEAD), null))
                .withMessageContaining("exactly one effective role");
    }

    @Test
    void approvalActivatesPendingAccountAndRecordsApproverAndTime() {
        UserAccount approver = new UserAccount("admin@brainserve.in", "Admin", null, "hash", false,
                AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_SYSTEM_ADMIN), null);
        UserAccount pending = new UserAccount("ceo@brainserve.in", "CEO", null, "hash", false,
                AccountStatus.PENDING_APPROVAL, Set.of(SystemRole.ROLE_CEO), approver);

        pending.approve(approver);

        assertThat(pending.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(pending.isEnabled()).isTrue();
        assertThat(pending.getApprovedByUser()).isSameAs(approver);
        assertThat(pending.getApprovedAt()).isNotNull();
    }

    @Test
    void rejectionKeepsAccountUnableToAuthenticate() {
        UserAccount pending = new UserAccount("security@brainserve.in", "Security", null, "hash", false,
                AccountStatus.PENDING_HR_APPROVAL, Set.of(SystemRole.ROLE_SECURITY), null);

        UserAccount rejector = new UserAccount("hr@brainserve.in", "HR", null, "hash", false,
                AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_HR_ADMIN), null);
        pending.reject(rejector);

        assertThat(pending.getStatus()).isEqualTo(AccountStatus.REJECTED);
        assertThat(pending.isEnabled()).isFalse();
        assertThat(pending.getRejectedByUser()).isSameAs(rejector);
        assertThat(pending.getRejectedAt()).isNotNull();
    }

    @Test
    void onlyAnActiveEmployeeLoginCanBecomeATeamLead() {
        UserAccount employee = new UserAccount("employee@brainserve.in", "Active Employee", UUID.randomUUID(),
                "hash", false, AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_EMPLOYEE), null);

        employee.promoteToTeamLead();

        assertThat(employee.getRoles()).containsExactly(SystemRole.ROLE_TEAM_LEAD);
    }

    @Test
    void receptionistAndSecurityLoginsCannotBecomeTeamLeads() {
        UserAccount receptionist = new UserAccount("reception@brainserve.in", "Reception", UUID.randomUUID(),
                "hash", false, AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_RECEPTIONIST), null);
        UserAccount security = new UserAccount("security@brainserve.in", "Security", UUID.randomUUID(),
                "hash", false, AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_SECURITY), null);

        assertThatIllegalStateException().isThrownBy(receptionist::promoteToTeamLead)
                .withMessageContaining("Only one active employee account");
        assertThatIllegalStateException().isThrownBy(security::promoteToTeamLead)
                .withMessageContaining("Only one active employee account");
    }

    @Test
    void operationalPromotionsReplaceTheRoleAndClearStaleOverrides() {
        UserAccount account = new UserAccount("career@brainserve.in", "Career Account", UUID.randomUUID(),
                "hash", false, AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_EMPLOYEE), null);
        account.replacePermissionOverrides(Set.of(Permission.SALARY_READ), Set.of());

        account.replaceOperationalRole(SystemRole.ROLE_TEAM_LEAD);
        assertThat(account.getRoles()).containsExactly(SystemRole.ROLE_TEAM_LEAD);
        assertThat(account.effectivePermissions()).doesNotContain(Permission.SALARY_READ);

        account.replaceOperationalRole(SystemRole.ROLE_HR_ADMIN);
        assertThat(account.getRoles()).containsExactly(SystemRole.ROLE_HR_ADMIN);

        account.replaceOperationalRole(SystemRole.ROLE_MANAGER);
        assertThat(account.getRoles()).containsExactly(SystemRole.ROLE_MANAGER);
    }

    @Test
    void formerChiefExecutiveBecomesActiveManagerWithoutChangingIdentity() {
        UUID employeeId = UUID.randomUUID();
        UserAccount account = new UserAccount("althuf@brainserve.in", "Althuf", employeeId,
                "existing-password-hash", false, AccountStatus.REJECTED,
                Set.of(SystemRole.ROLE_CEO), null);
        account.replacePermissionOverrides(Set.of(Permission.SALARY_READ), Set.of());

        account.replaceFormerChiefExecutiveWithManager();

        assertThat(account.getEmail()).isEqualTo("althuf@brainserve.in");
        assertThat(account.getEmployeeId()).isEqualTo(employeeId);
        assertThat(account.getPasswordHash()).isEqualTo("existing-password-hash");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isEnabled()).isTrue();
        assertThat(account.getRoles()).containsExactly(SystemRole.ROLE_MANAGER);
        assertThat(account.effectivePermissions()).doesNotContain(Permission.SALARY_READ);
    }
}
