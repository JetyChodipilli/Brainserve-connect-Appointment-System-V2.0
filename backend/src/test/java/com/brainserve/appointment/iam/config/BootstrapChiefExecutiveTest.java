package com.brainserve.appointment.iam.config;

import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BootstrapChiefExecutiveTest {
    @Test
    void createsOneActiveChiefExecutiveAndDoesNotDuplicateOnRerun() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.findGoverningRoleAccountsForUpdate(
                SystemRole.ROLE_CEO, Set.of(AccountStatus.ACTIVE, AccountStatus.PENDING_APPROVAL)))
                .thenReturn(List.of(), List.of(mock(UserAccount.class)));
        when(users.existsByEmailIgnoreCase("ceo@brainserve.in")).thenReturn(false);
        when(encoder.encode("TestCeo!Pass2026")).thenReturn("bcrypt-ceo");
        BootstrapChiefExecutive bootstrap = new BootstrapChiefExecutive(users, encoder, true,
                "BrainServe CEO", "ceo@brainserve.in", "TestCeo!Pass2026");

        bootstrap.run(null);
        bootstrap.run(null);

        ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(account.capture());
        verify(encoder).encode("TestCeo!Pass2026");
        assertThat(account.getValue().getFullName()).isEqualTo("BrainServe CEO");
        assertThat(account.getValue().getEmail()).isEqualTo("ceo@brainserve.in");
        assertThat(account.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getValue().getRoles()).containsExactly(SystemRole.ROLE_CEO);
        assertThat(account.getValue().getEmployeeId()).isNull();
    }

    @Test
    void leavesAnExistingChiefExecutiveUntouched() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.findGoverningRoleAccountsForUpdate(
                SystemRole.ROLE_CEO, Set.of(AccountStatus.ACTIVE, AccountStatus.PENDING_APPROVAL)))
                .thenReturn(List.of(mock(UserAccount.class)));
        BootstrapChiefExecutive bootstrap = new BootstrapChiefExecutive(users, encoder, true,
                "BrainServe CEO", "ceo@brainserve.in", "TestCeo!Pass2026");

        bootstrap.run(null);

        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }
}
