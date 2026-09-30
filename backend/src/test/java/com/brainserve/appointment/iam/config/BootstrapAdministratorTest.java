package com.brainserve.appointment.iam.config;

import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class BootstrapAdministratorTest {
    @Test
    void createsSystemAdminExactlyOnceAcrossRepeatedRuns() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.existsByEmailIgnoreCase(BootstrapAdministrator.SYSTEM_ADMIN_EMAIL)).thenReturn(false, true);
        when(encoder.encode("Initial!Admin2026")).thenReturn("bcrypt-hash");
        BootstrapAdministrator bootstrap = new BootstrapAdministrator(users, encoder, "Initial!Admin2026", true);

        bootstrap.run(null);
        bootstrap.run(null);

        ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(account.capture());
        verify(encoder).encode("Initial!Admin2026");
        assertThat(account.getValue().getEmail()).isEqualTo("jetychodipilli@gmail.com");
        assertThat(account.getValue().getFullName()).isEqualTo("Jety Chodipilli");
        assertThat(account.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getValue().isEnabled()).isTrue();
        assertThat(account.getValue().isForcePasswordChange()).isFalse();
        assertThat(account.getValue().getRoles()).containsExactly(SystemRole.ROLE_SYSTEM_ADMIN);
        assertThat(account.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");
    }

    @Test
    void leavesAnExistingSystemAdminUntouchedEvenWhenBootstrapPasswordIsUnavailable() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.existsByEmailIgnoreCase(BootstrapAdministrator.SYSTEM_ADMIN_EMAIL)).thenReturn(true);
        BootstrapAdministrator bootstrap = new BootstrapAdministrator(users, encoder, "", true);

        bootstrap.run(null);

        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }
}
