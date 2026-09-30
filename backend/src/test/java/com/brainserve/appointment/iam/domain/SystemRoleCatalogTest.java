package com.brainserve.appointment.iam.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemRoleCatalogTest {
    @Test
    void exposesExactlyTheEightSupportedBrainServeRoles() {
        assertThat(SystemRole.values()).containsExactlyInAnyOrder(
                SystemRole.ROLE_SYSTEM_ADMIN, SystemRole.ROLE_CEO, SystemRole.ROLE_MANAGER,
                SystemRole.ROLE_HR_ADMIN,
                SystemRole.ROLE_TEAM_LEAD, SystemRole.ROLE_EMPLOYEE,
                SystemRole.ROLE_RECEPTIONIST, SystemRole.ROLE_SECURITY);
    }

    @Test
    void visitorWorkflowPermissionsRemainSeparatedByRole() {
        assertThat(SystemRole.ROLE_HR_ADMIN.permissions())
                .contains(Permission.STAFF_ACCOUNT_APPROVE, Permission.WORK_TASK_CREATE,
                        Permission.WORK_INSIGHT_AUDIT);
        assertThat(SystemRole.ROLE_RECEPTIONIST.permissions())
                .contains(Permission.RECEPTION_VISIT_VERIFY, Permission.QR_PASS_VERIFY)
                .doesNotContain(Permission.SECURITY_VISITOR_INTAKE, Permission.HR_VISIT_APPROVE,
                        Permission.CEO_VISIT_APPROVE);
        assertThat(SystemRole.ROLE_SECURITY.permissions())
                .contains(Permission.SECURITY_VISITOR_INTAKE, Permission.QR_PASS_VERIFY)
                .doesNotContain(Permission.RECEPTION_VISIT_VERIFY, Permission.HR_VISIT_APPROVE,
                        Permission.CEO_VISIT_APPROVE);
        assertThat(SystemRole.ROLE_HR_ADMIN.permissions()).doesNotContain(Permission.CEO_VISIT_APPROVE);
        assertThat(SystemRole.ROLE_CEO.permissions()).doesNotContain(Permission.HR_VISIT_APPROVE);
        assertThat(SystemRole.ROLE_CEO.permissions()).doesNotContain(Permission.STAFF_ACCOUNT_MANAGE);
        assertThat(SystemRole.ROLE_TEAM_LEAD.permissions())
                .contains(Permission.TEAM_LEAD_VISIT_APPROVE, Permission.TEAM_LEAD_DIRECTORY_VIEW)
                .doesNotContain(Permission.HR_VISIT_APPROVE, Permission.STAFF_ACCOUNT_MANAGE);
        assertThat(SystemRole.ROLE_MANAGER.permissions())
                .contains(Permission.MANAGER_VISIT_APPROVE, Permission.EMPLOYEE_READ,
                        Permission.WORK_TASK_READ, Permission.WORK_INSIGHT_MANAGER_APPROVE)
                .doesNotContain(Permission.CEO_VISIT_APPROVE, Permission.SALARY_READ,
                        Permission.STAFF_ACCOUNT_MANAGE, Permission.ROLE_MANAGE,
                        Permission.WORK_INSIGHT_AUDIT);
    }
}
