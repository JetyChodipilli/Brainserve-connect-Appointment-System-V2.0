package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticationServiceProfileAssignmentTest {
    @Test
    void blocksAnApprovedEmployeeUntilHrAssignsDepartmentAndEmployeeId() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        RefreshTokenSessionRepository sessions = mock(RefreshTokenSessionRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        UserAccount employee = new UserAccount("employee@brainserve.in", "Approved Employee", null,
                "bcrypt-hash", false, AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_EMPLOYEE), null);
        when(users.findByEmailIgnoreCase("employee@brainserve.in")).thenReturn(Optional.of(employee));
        when(encoder.matches("Employee!Pass2026", "bcrypt-hash")).thenReturn(true);
        AuthenticationService service = new AuthenticationService(users, sessions, encoder, jwt,
                mock(CompanyEmailPolicy.class), mock(EmailService.class), mock(StringRedisTemplate.class),
                mock(AuthenticationSecurityStateWriter.class), 14, 10);

        assertThatThrownBy(() -> service.login("employee@brainserve.in", "Employee!Pass2026"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.getErrorCode())
                            .isEqualTo("EMPLOYEE_PROFILE_ASSIGNMENT_REQUIRED");
                    org.assertj.core.api.Assertions.assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        verifyNoInteractions(jwt, sessions);
    }
}
