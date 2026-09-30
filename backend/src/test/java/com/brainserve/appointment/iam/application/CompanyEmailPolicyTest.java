package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanyEmailPolicyTest {
    @Test
    void appliesTheRuntimeWorkspaceDomainToNewStaffAccounts() {
        WorkspacePolicy workspace = mock(WorkspacePolicy.class);
        when(workspace.stringValue("COMPANY.EMAIL_DOMAIN", "brainserve.in")).thenReturn("new-company.example");
        CompanyEmailPolicy policy = new CompanyEmailPolicy("brainserve.in", workspace);

        assertThat(policy.requireCompanyEmail(" Person@NEW-COMPANY.EXAMPLE "))
                .isEqualTo("person@new-company.example");
        assertThatThrownBy(() -> policy.requireCompanyEmail("person@brainserve.in"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("@new-company.example");
    }
}
