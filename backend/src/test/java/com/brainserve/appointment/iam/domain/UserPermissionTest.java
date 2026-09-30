package com.brainserve.appointment.iam.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserPermissionTest {
    @Test
    void systemAdministratorDoesNotReceiveSalaryAccessByDefault() {
        UserAccount user = new UserAccount("admin@brainserve.in", null, "hash", false, Set.of(SystemRole.ROLE_SYSTEM_ADMIN));
        assertThat(user.effectivePermissions()).doesNotContain(Permission.SALARY_READ, Permission.SALARY_WRITE, Permission.SALARY_APPROVE);
    }

    @Test
    void explicitDenyRemovesRolePermissionAndGrantAddsIndependentPermission() {
        UserAccount user = new UserAccount("hr@brainserve.in", null, "hash", false, Set.of(SystemRole.ROLE_HR_ADMIN));
        user.replacePermissionOverrides(Set.of(Permission.SYSTEM_CONFIGURE), Set.of(Permission.SALARY_READ));
        assertThat(user.effectivePermissions()).contains(Permission.SYSTEM_CONFIGURE).doesNotContain(Permission.SALARY_READ);
    }

    @Test
    void receptionistCannotApproveAndHrCannotApproveForCeo() {
        UserAccount receptionist = new UserAccount("reception@brainserve.in", null, "hash", false,
                Set.of(SystemRole.ROLE_RECEPTIONIST));
        UserAccount hr = new UserAccount("hr@brainserve.in", null, "hash", false,
                Set.of(SystemRole.ROLE_HR_ADMIN));
        UserAccount ceo = new UserAccount("ceo@brainserve.in", null, "hash", false,
                Set.of(SystemRole.ROLE_CEO));

        assertThat(receptionist.effectivePermissions()).doesNotContain(Permission.HR_VISIT_APPROVE,
                Permission.CEO_VISIT_APPROVE);
        assertThat(hr.effectivePermissions()).contains(Permission.HR_VISIT_APPROVE,
                Permission.STAFF_ACCOUNT_MANAGE).doesNotContain(Permission.CEO_VISIT_APPROVE);
        assertThat(ceo.effectivePermissions()).contains(Permission.CEO_VISIT_APPROVE)
                .doesNotContain(Permission.STAFF_ACCOUNT_MANAGE, Permission.ROLE_MANAGE,
                        Permission.SYSTEM_CONFIGURE);
    }
}
